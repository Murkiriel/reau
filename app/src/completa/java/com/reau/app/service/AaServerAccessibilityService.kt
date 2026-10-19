package com.reau.app.service

import kotlinx.coroutines.flow.update
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.util.size
import androidx.core.view.doOnPreDraw
import com.reau.app.MainActivity
import com.reau.app.R
import com.reau.app.aa.AndroidAuto
import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerNotification
import com.reau.app.data.RideDiagnostics

/**
 * REau's accessibility service, for one thing only (switching Android Auto's head unit server on or off): walking
 * Android Auto's settings menu to "Iniciar" or "Parar
 * servidor da unidade principal" ([ServerMenuRun] decides every step, this carries them out), and keeping the "Stop"
 * of that server's notification. Its configuration (res/xml/aa_server_accessibility.xml) lets it hear Android Auto
 * and the voice apps' call notifications (the ride log only, [voiceCall]); it reads no other app's windows
 * and touches nothing outside a walk the starter ([AaServerStarter]) asked for.
 *
 * While a walk goes on, an opaque cover (an accessibility overlay: no extra permission) hides Android Auto's screen,
 * takes the rider's touches so none lands on the menu by mistake, and offers "Cancelar". The clicks are actions on the
 * nodes, which the cover does not block.
 */
// Android's callbacks, the walk's steps and the cover: all one service's, around the same walk.
@Suppress("TooManyFunctions")
class AaServerAccessibilityService : AccessibilityService(), AaServerStarter.MenuDriver {
    /** The app whose menu is walked and how its screen opens: Android Auto, or a test's own screen. */
    internal class Target(val packageName: String, val open: () -> Intent)

    companion object {
        /** The running service, while Android keeps it connected. */
        @Volatile private var connected: AaServerAccessibilityService? = null

        /** The calls switch changed (MainActivity): the running service hears the voice apps or stops hearing them. */
        fun followCallsSwitch(answerCalls: Boolean) {
            val service = connected ?: return
            service.main.post { service.hear(answerCalls) }
        }

        /** Test-only: whether the running service's cover is up (main thread). */
        internal fun testOnlyCoverShown(): Boolean = connected?.cover?.shown == true

        /** Test-only: the apps the running service hears now, or null when it is not connected. */
        internal fun testOnlyHeard(): Set<String>? = connected?.serviceInfo?.packageNames?.toSet()

        /** Tests point it at their own screen. */
        @Volatile internal var target = Target(AndroidAuto.PACKAGE) { AndroidAuto.settingsIntent() }

        private const val TAG = "aa-server"
        private const val POLL_MS = 250L

        /** The cover stays this long after a walk, over the way back and Android Auto's "Iniciando servidor…". */
        private const val COVER_AFTER_MS = 1_000L

        /** Whatever happens, the cover goes after this long. */
        private const val COVER_WATCHDOG_MS = 16_000L

        /** A screen is never read past this many nodes. */
        private const val MAX_NODES = 600

        /** After the hold, a little more before reading the screen again. */
        private const val READ_AGAIN_SLACK_MS = 50L
    }

    private val main = Handler(Looper.getMainLooper())
    private var run: ServerMenuRun? = null
    private var done: ((ServerMenuRun.Outcome) -> Unit)? = null
    private var begunMs = 0L
    private var lastChangeMs = 0L

    // The nodes of the last look, by SeenNode.ref: what a Click step clicks.
    private val refs = mutableListOf<AccessibilityNodeInfo>()
    private val cover = Cover()

    // A cover put up at the rider's tap, before its walk (TapCover), and when it goes if no walk comes.
    private var held = false
    private val heldGone = Runnable { dropCover() }
    private val poll = object : Runnable {
        override fun run() {
            if (run == null) return
            tick()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AaServerStarter.driver = this
        // The screen read again after inputs and every second while the projection runs
        com.reau.app.aa.AaScreens.rereader = rereader
        if (com.reau.app.aa.AaScreens.projecting) rereader.projection(true)
        log("accessibility service connected")
        connected = this
        AaServerStarter.serviceChanges.update { it + 1 }
        hear(com.reau.app.settings.SettingsStore.open(this).load().answerCalls)
        // The server card was up for want of this service: the walk is made again now, without the rider's touch.
        AaServerStarter.serviceConnected(applicationContext)
    }

    /** For privacy, the apps heard narrowed to [AccessibilityScope] for the calls switch. */
    private fun hear(answerCalls: Boolean) {
        val info = serviceInfo ?: return
        val apps = AccessibilityScope.packages(answerCalls)
        info.packageNames = apps.toTypedArray()
        serviceInfo = info
        log("the accessibility service hears: ${apps.sorted().joinToString(", ")}")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        gone("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone("destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return notification(event)
        // The voice apps are heard for their call notifications only, never for their windows
        if (event.packageName?.toString() in com.reau.app.phone.CallNotice.VOICE_APPS) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            readScreen()
        }
        if (run == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.packageName?.toString() == target.packageName) {
            lastChangeMs = SystemClock.elapsedRealtime()
        }
        tick()
    }

    override fun drive(run: ServerMenuRun, done: (ServerMenuRun.Outcome) -> Unit): Boolean {
        if (this.run != null) return false
        this.run = run
        this.done = done
        begunMs = SystemClock.elapsedRealtime()
        lastChangeMs = 0
        cover.acquire(com.reau.app.ui.ScreenText.cover(run.mode), run.mode, begunMs, "the walk began")
        // A cover already up from the rider's tap is the walk's now.
        dropCover()
        tick()
        main.postDelayed(poll, POLL_MS)
        return true
    }

    override fun holdCover(mode: ServerMenuRun.Mode, maxMs: Long) {
        if (held || run != null) return
        held = true
        cover.acquire(com.reau.app.ui.ScreenText.cover(mode), mode, SystemClock.elapsedRealtime(), "the rider's tap")
        main.postDelayed(heldGone, maxMs)
    }

    override fun dropCover() {
        if (!held) return
        held = false
        main.removeCallbacks(heldGone)
        cover.release(0)
    }

    private fun gone(why: String) {
        if (connected === this) connected = null
        if (AaServerStarter.driver === this) AaServerStarter.driver = null
        AaServerStarter.serviceChanges.update { it + 1 }
        if (com.reau.app.aa.AaScreens.rereader === rereader) com.reau.app.aa.AaScreens.rereader = null
        main.removeCallbacks(rereadTick)
        if (run != null) end(ServerMenuRun.Outcome.CANCELLED)
        cover.removeNow()
        log("accessibility service $why")
    }

    /** One step of the walk, from what the screen holds now. */
    private fun tick() {
        val walk = run ?: return
        cover.step(walk.coverStep)
        val seen = look(walk.target)
        when (val step = walk.next(seen)) {
            ServerMenuRun.Step.Wait -> Unit
            ServerMenuRun.Step.OpenSettings -> open(walk, seen)
            is ServerMenuRun.Step.Click -> {
                val clicked = refs.getOrNull(step.node.ref)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                step(seen, "clicked ${step.what}" + if (clicked) "" else " (refused)")
                if (!clicked) walk.clickRefused(seen.nowMs)
            }
            ServerMenuRun.Step.Back -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                step(seen, "back")
            }
            ServerMenuRun.Step.OpenREau -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                step(seen, "REau brought back")
                tick()
            }
            is ServerMenuRun.Step.Done -> end(step.outcome)
        }
    }

    private fun open(walk: ServerMenuRun, seen: ServerMenuRun.Seen) {
        val refusal = try {
            startActivity(target.open())
            null
        } catch (e: ActivityNotFoundException) {
            e
        } catch (e: SecurityException) {
            e
        }
        if (refusal == null) return step(seen, "opened the settings")
        log("AaServerStarter: settings did not open (${refusal.javaClass.simpleName}: ${refusal.message})")
        walk.openFailed()
        tick()
    }

    private fun end(outcome: ServerMenuRun.Outcome) {
        val finished = done
        run = null
        done = null
        refs.clear()
        main.removeCallbacks(poll)
        cover.step(ALL_STEPS_DONE)
        cover.release(if (outcome == ServerMenuRun.Outcome.CANCELLED) 0 else COVER_AFTER_MS)
        finished?.invoke(outcome)
    }

    private fun step(seen: ServerMenuRun.Seen, what: String) = log("AaServerStarter: +${seen.nowMs - begunMs} ms $what")

    /** What the screen holds: the window in front, and every node with a text or a description in [packageName]'s windows. */
    private fun look(packageName: String): ServerMenuRun.Seen {
        refs.clear()
        val nodes = mutableListOf<ServerMenuRun.Node>()
        val active = rootInActiveWindow
        // Never the cover: it is this service's own window, and on a test's screen it would read as the menu's list.
        val roots = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .mapNotNull { window -> window.root?.let { window.id to it } }
            .ifEmpty { listOfNotNull(active?.let { it.windowId to it }) }
        for ((window, root) in roots) {
            if (root.packageName?.toString() == packageName) collect(root, window, null, nodes)
        }
        return ServerMenuRun.Seen(
            nowMs = SystemClock.elapsedRealtime(),
            front = active?.packageName?.toString(),
            locked = locked(),
            nodes = nodes,
            lastChangeMs = lastChangeMs,
        )
    }

    private fun collect(node: AccessibilityNodeInfo, window: Int, clickable: AccessibilityNodeInfo?, into: MutableList<ServerMenuRun.Node>) {
        if (into.size >= MAX_NODES) return
        val clickTarget = if (node.isClickable) node else clickable
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
            into += ServerMenuRun.Node(window, text, description, clickable = clickTarget != null, ref = refs.size)
            refs += clickTarget ?: node
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, window, clickTarget, into) }
    }

    private fun locked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true || getSystemService(PowerManager::class.java)?.isInteractive == false

    /** Android Auto's server notification; its one action, "Stop", is kept for the end of the projection. */
    private fun notification(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != AndroidAuto.PACKAGE) return pkg?.let { voiceCall(it, event) } ?: Unit
        val notification = event.parcelableData as? Notification ?: return
        val action = notification.actions?.singleOrNull()
        val intent = action?.actionIntent
        val seen = ServerNotification.Seen(
            channel = notification.channelId,
            actions = notification.actions?.size ?: 0,
            creator = intent?.creatorPackage,
            isService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) intent?.isService else null,
            actionTitle = action?.title?.toString(),
            text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        if (intent != null && ServerNotification.isServerStop(seen, AaServerStarter.texts(this))) {
            AaServerStarter.stopButton = intent
            log("AaServerStarter: the server's notification is up, its Stop kept")
        }
    }

    /**
     * A voice app's call notification, said in the ride log with the switch "Atender ligação pelo joystick" on: its
     * style, type and buttons, and whether Telecom sees a call; never its title or text (the caller). For measuring
     * these notifications on a phone before the joystick answers them.
     */
    private fun voiceCall(pkg: String, event: AccessibilityEvent) {
        val notification = event.parcelableData as? Notification ?: return
        if (!com.reau.app.phone.CallNotice.isCall(pkg, notification.category)) return
        if (!com.reau.app.settings.SettingsStore.open(this).load().answerCalls) return
        val extras = notification.extras
        val phone = com.reau.app.phone.PhoneCalls(this)
        val notice = com.reau.app.phone.CallNotice(
            pkg = pkg,
            template = extras?.getString(com.reau.app.phone.CallNotice.EXTRA_TEMPLATE),
            callType = extras?.takeIf { it.containsKey(com.reau.app.phone.CallNotice.EXTRA_CALL_TYPE) }
                ?.getInt(com.reau.app.phone.CallNotice.EXTRA_CALL_TYPE),
            answer = extras?.containsKey(com.reau.app.phone.CallNotice.EXTRA_ANSWER_INTENT) == true,
            decline = extras?.containsKey(com.reau.app.phone.CallNotice.EXTRA_DECLINE_INTENT) == true,
            hangUp = extras?.containsKey(com.reau.app.phone.CallNotice.EXTRA_HANG_UP_INTENT) == true,
            actionTitles = notification.actions.orEmpty().map { it.title?.toString().orEmpty() },
            telecomInCall = phone.telecomInCall(),
        )
        RideDiagnostics.log("call", notice.line())
    }

    private fun log(line: String) = RideDiagnostics.log(TAG, line)

    // The last raw reading said in the log, so a ride logs each change of Android Auto's displays once.
    private var lastRaw = ""
    private val readAgain = Runnable { readScreen() }

    // Reads without a window event, on this service's main thread only.
    private val rereads = com.reau.app.aa.AaScreenRereads()
    private val rereadTick: Runnable = Runnable {
        if (rereads.due(SystemClock.elapsedRealtime())) readScreen(reread = true)
        planRereads()
    }
    private val rereader = object : com.reau.app.aa.AaScreens.Rereader {
        override fun inputSent() {
            main.post {
                rereads.inputSent(SystemClock.elapsedRealtime())
                planRereads()
            }
        }

        override fun projection(on: Boolean) {
            main.post {
                rereads.projection(on, SystemClock.elapsedRealtime())
                planRereads()
            }
        }
    }

    /** One pending re-read at a time, at the next time [rereads] asks for. */
    private fun planRereads() {
        main.removeCallbacks(rereadTick)
        val at = rereads.nextAt() ?: return
        main.postDelayed(rereadTick, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    /**
     * Which of Android Auto's screens is shown, from the display whose window has focus
     * (each screen of Android Auto is a virtual display of its own). Android 11+ (windows of every display). The system
     * says a window change once: while a new screen waits to hold, it is read again after the hold.
     */
    private fun readScreen(reread: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val byDisplay = runCatching { windowsOnAllDisplays }.getOrNull() ?: return
        val names = getSystemService(android.hardware.display.DisplayManager::class.java)
        val displays = (0 until byDisplay.size).map { i ->
            val id = byDisplay.keyAt(i)
            com.reau.app.aa.AaScreenRule.Display(id, names?.getDisplay(id)?.name.orEmpty(), byDisplay.valueAt(i).any { it.isFocused })
        }
        val raw = displays.filter { com.reau.app.aa.AaScreenRule.PROJECTED in it.name }
            .joinToString { "${it.id} ${com.reau.app.aa.AaScreenRule.shortName(it.name)}${if (it.focused) " focused" else ""}" }
        if (raw != lastRaw) {
            lastRaw = raw
            RideDiagnostics.log("aa-screen", "displays: ${raw.ifEmpty { "none of Android Auto's" }}")
        }
        val watch = com.reau.app.aa.AaScreens.watch
        watch.update(displays, SystemClock.elapsedRealtime(), reread)
        main.removeCallbacks(readAgain)
        if (watch.waiting) main.postDelayed(readAgain, com.reau.app.aa.AaScreens.HOLD_MS + READ_AGAIN_SLACK_MS)
    }

    /**
     * The opaque cover over the walk: counted, so a restart's two halves share one; it goes
     * [COVER_AFTER_MS] after the walk, and after [COVER_WATCHDOG_MS] whatever happens.
     */
    private inner class Cover {
        private var count = 0
        private var view: ServerCoverView? = null
        private val watchdog = Runnable { removeNow() }

        val shown: Boolean get() = view != null

        /**
         * Shown with [text] (switching on or off) for a walk in [mode]; a cover already up takes the new walk's text.
         * The log says how long after [sinceMs] ([since]: the walk's start, or the rider's tap) it was drawn.
         */
        fun acquire(text: Int, mode: ServerMenuRun.Mode, sinceMs: Long, since: String) {
            count++
            view?.setText(text) ?: show(text, mode, sinceMs, since)
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, COVER_WATCHDOG_MS)
        }

        /** Where the walk is ([ServerMenuRun.coverStep]). */
        fun step(step: Int) {
            view?.setStep(step)
        }

        fun release(afterMs: Long) {
            main.postDelayed({
                if (count > 0) count--
                if (count == 0) removeNow()
            }, afterMs)
        }

        fun removeNow() {
            count = 0
            main.removeCallbacks(watchdog)
            val shown = view ?: return
            view = null
            runCatching { getSystemService(WindowManager::class.java).removeView(shown) }
        }

        private fun show(cover: Int, mode: ServerMenuRun.Mode, sinceMs: Long, since: String) {
            val layout = ServerCoverView(this@AaServerAccessibilityService, mode) {
                log("AaServerStarter: the rider cancelled")
                if (run == null && held) {
                    AaServerStarter.cancelledBeforeTheWalk()
                    dropCover()
                }
                run?.cancel()
                tick()
            }.apply { setText(cover) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never focusable: "back" must go to Android Auto, not to the cover. Touches are taken (no NOT_TOUCHABLE).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                getSystemService(WindowManager::class.java).addView(layout, params)
                view = layout
                // How long the cover took to appear, for the ride log.
                layout.doOnPreDraw { log("AaServerStarter: the cover drawn ${SystemClock.elapsedRealtime() - sinceMs} ms after $since") }
            } catch (e: WindowManager.BadTokenException) {
                log("AaServerStarter: the cover could not be shown (${e.message}); the walk goes on without it")
            }
        }
    }
}

/** The cover's three steps, all done. */
private const val ALL_STEPS_DONE = 3
