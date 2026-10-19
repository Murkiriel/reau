package com.reau.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AndroidAuto
import com.reau.app.aa.MuteServerFix
import com.reau.app.aa.PortWait
import com.reau.app.aa.ServerGate
import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerPolicy
import com.reau.app.aa.ServerResult
import com.reau.app.aa.ServerTexts
import com.reau.app.aa.TapCover
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the projection's sides tell the switch of Android Auto's head unit server. */
interface AaServerHooks {
    /** Android Auto's side switched on; [dialSoon] makes the receiver dial at once. */
    fun started(dialSoon: () -> Unit) = Unit

    /** Each state of the link to Android Auto. */
    fun linkChanged(state: AaLinkState) = Unit

    /** Android Auto's side switched off, the receiver already stopped. */
    fun stopped() = Unit

    /** Nothing is switched: tests, and the card mode. */
    object None : AaServerHooks
}

/**
 * Switches Android Auto's head unit server on and off by itself (the accessibility service is used for that alone),
 * behind the switch "Ligar e desligar o servidor do Android Auto sozinho", off by default:
 *
 * - the projection's first dial finds the port closed: one walk through Android Auto's menu, "⋮ → Iniciar servidor
 *   da unidade principal" ([ServerMenuRun], carried out by [AaServerAccessibilityService]);
 * - Android Auto accepts and does not answer: one restart ("stop", a pause, "start");
 * - the projection ends with the server seen up: the notification's own "Stop" (no screen), or the menu's "stop".
 *
 * Nothing is tried with the phone locked or the accessibility service not connected: the screen and the log say why,
 * and the manual way (the button to Android Auto's settings) stays. The developer mode's 10 taps are never made.
 */
// The hooks of the projection, the screen's actions and the walk's start and end: one starter, one state.
@Suppress("TooManyFunctions")
object AaServerStarter {
    /** Whether REau's accessibility service runs: connected, switched on in Android but not connected, or off. */
    enum class Accessibility { CONNECTED, ENABLED, OFF }

    /**
     * Counts each time the accessibility service connects or goes (after installing, "Permissões" said "Falta 1" until
     * the screen was left and opened again): Android binds the service some seconds after the app starts, so the
     * screens read [accessibility] again at each change instead of only when they come to the front.
     */
    val serviceChanges = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** What the accessibility service does for the starter. */
    internal interface MenuDriver {
        /** Walks [run] behind the cover; [done] gets its outcome on the main thread. False if a walk is going on. */
        fun drive(run: ServerMenuRun, done: (ServerMenuRun.Outcome) -> Unit): Boolean

        /** Puts the cover of a walk in [mode] up now, before the walk ([TapCover]); it goes by itself after [maxMs]. */
        fun holdCover(mode: ServerMenuRun.Mode, maxMs: Long) = Unit

        /** Takes down a cover put up by [holdCover] when no walk comes; a walk that begins takes it over instead. */
        fun dropCover() = Unit
    }

    private const val TAG = "aa-server"

    // Android's page for one accessibility service (API 31+, not in the SDK's public constants); the list if it fails.
    private const val ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** How long the port has to answer after "start" was touched. */
    private const val PORT_WAIT_MS = 5_000L

    /** How long a cover put up at the rider's tap waits for its walk (on Desligar, the session's end comes first). */
    private const val TAP_COVER_MS = 8_000L

    // When the rider touched Cancelar on a cover put up at the tap, before its walk: that walk is not made.
    @Volatile private var cancelledAtTapMs = 0L

    // A Ligar came after the last Desligar, before the new projection began (observed on a device):
    // the projection still ending leaves the server on, the new one needs it.
    @Volatile private var ligarAfterDesligar = false

    // A walk asked for while another went on (main thread): made right after it, not lost (observed on a device: a start
    // refused under the end of a stop).
    private var next: (() -> Unit)? = null

    // Elapsed realtime of the last touch of the server's "Stop" (the notification's or the menu's) and of the last end
    // of a projection; 0: none yet. Only for the log: Android Auto fell over with "Already connected" 0.43 s after a start
    // that came 4 s after a stop, and these times tell how close the two were.
    @Volatile private var lastStopTapMs = 0L
    @Volatile private var lastProjectionEndMs = 0L

    private val result = MutableStateFlow(ServerResult.NONE)

    /** The last thing tried and how it went, for the main screen. */
    val status: StateFlow<ServerResult> = result.asStateFlow()

    /** Whether Android Auto's settings open on this phone; tests whose emulator has only a stub of it say yes. */
    @Volatile internal var settingsOpen: (Context) -> Boolean = AndroidAuto::settingsOpen

    /** Set by the accessibility service while it is connected. */
    @Volatile internal var driver: MenuDriver? = null

    /** Android Auto's "Stop" of its server's notification, kept in memory only. */
    @Volatile internal var stopButton: PendingIntent? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val policy = ServerPolicy()
    private val lock = Any()

    // The projection's receiver, told to dial at once after "start" was touched. Internal for AaServerStarterProjectionsTest.
    @Volatile internal var dialSoon: (() -> Unit)? = null

    // Whether the port answered after a touched "start" (main thread).
    private val portWait = PortWait()

    /**
     * The projection switched Android Auto's side on: each try once more. [dial] makes the receiver dial now. Returns
     * the projection's number, which its [linkChanged] and [projectionStopped] carry.
     */
    fun projectionStarted(dial: () -> Unit): Int = synchronized(lock) {
        dialSoon = dial
        ligarAfterDesligar = false
        policy.started()
    }

    /** Each state of the link to Android Auto (any thread), from projection [of]. */
    fun linkChanged(context: Context, state: AaLinkState, of: Int) {
        val act = synchronized(lock) { policy.state(state, of) }
        // Android Auto answered without a walk: a cover put up at the tap has nothing to wait for.
        if (state == AaLinkState.ACTIVE) dropTapCover()
        when (act) {
            ServerPolicy.Act.START -> walkIfOn(context, ServerMenuRun.Mode.START, "the port is closed")
            ServerPolicy.Act.RESTART -> walkIfOn(context, ServerMenuRun.Mode.START, "Android Auto accepts and does not answer: restart")
            ServerPolicy.Act.ANSWERED -> main.post { answered() }
            ServerPolicy.Act.NOTHING -> Unit
        }
    }

    /**
     * Projection [of] switched Android Auto's side off (after the receiver stopped: the server's end would drop a
     * session). Switches the server off if it was seen up — never when a newer projection already began (off
     * and on again in under 2 s, this end comes after the new start, and the server is the new one's).
     */
    fun projectionStopped(context: Context, of: Int) {
        lastProjectionEndMs = SystemClock.elapsedRealtime()
        val (current, wasUp) = synchronized(lock) {
            val current = policy.isCurrent(of)
            if (current) dialSoon = null
            current to policy.stopped(of)
        }
        when {
            !current -> log("AaServerStarter: a newer projection already began; the server stays on")
            ligarAfterDesligar -> log("AaServerStarter: the rider switched on again before this end; the server stays on")
            !switchOn(context) -> Unit
            !wasUp -> log("AaServerStarter: the server was not seen up, nothing to switch off")
            !pressStop() -> return walk(context, ServerMenuRun.Mode.STOP, "the projection ended", leftOnIfRefused = true)
        }
        dropTapCover()
    }

    /** The notification's "Stop", if one is kept: whether it was sent. */
    private fun pressStop(): Boolean {
        val button = stopButton ?: return false
        return try {
            button.send()
            lastStopTapMs = SystemClock.elapsedRealtime()
            result.value = ServerResult.STOPPED
            log("AaServerStarter: server switched off by the notification's Stop")
            true
        } catch (e: PendingIntent.CanceledException) {
            stopButton = null
            log("AaServerStarter: the notification's Stop is gone (${e.message}); the menu instead")
            false
        }
    }

    /** What a projection on the phone tells the starter. */
    fun hooks(context: Context): AaServerHooks {
        val app = context.applicationContext
        return object : AaServerHooks {
            // This projection's number: what it says while it ends never reaches a newer one.
            @Volatile private var mine = 0
            override fun started(dialSoon: () -> Unit) {
                mine = projectionStarted(dialSoon)
            }
            override fun linkChanged(state: AaLinkState) = linkChanged(app, state, mine)
            override fun stopped() = projectionStopped(app, mine)
        }
    }

    /**
     * The rider's Ligar or Desligar ([on]), as the projection service receives it: the walk's cover goes up at once
     * when [TapCover] says a walk is sure to follow, so the switch also looks fast.
     */
    fun riderSwitched(context: Context, on: Boolean) {
        val app = context.applicationContext
        ligarAfterDesligar = on
        val mode = synchronized(lock) { TapCover.mode(on, switchOn(app), result.value, policy.serverSeenUp(), stopButton != null) }
        main.post {
            cancelledAtTapMs = 0
            val running = driver ?: return@post
            if (mode == null || ServerGate.refusal(ServerGate.Phone(true, locked(app), settingsOpen(app))) != null) return@post
            log("AaServerStarter: the cover up at the rider's ${if (on) "Ligar" else "Desligar"}, before its walk")
            running.holdCover(mode, TAP_COVER_MS)
        }
    }

    /** Cancelar on a cover put up at the tap, before its walk (the service, on the main thread): that walk is not made. */
    internal fun cancelledBeforeTheWalk() {
        cancelledAtTapMs = SystemClock.elapsedRealtime()
        log("AaServerStarter: the rider cancelled before the walk; it will not be made")
    }

    /** No walk comes after all: the cover put up at the tap, if any, goes. */
    private fun dropTapCover() {
        main.post { driver?.dropCover() }
    }

    /**
     * REau's accessibility service has just connected (the full edition only; main thread). If the last try found the
     * service off and the link still needs it (the port closed: the start; Android Auto accepting and mute: the restart,
     * whose one try per projection that refusal spent), the try is made again by itself: the rider who switched the
     * service on in Android's settings and came back need not touch "Tentar de novo".
     */
    fun serviceConnected(context: Context) {
        val again = synchronized(lock) { policy.retryOnServiceConnected(result.value) }
        if (!again) return
        log("AaServerStarter: the accessibility service connected after a try it had refused; trying again by itself")
        walkIfOn(context, ServerMenuRun.Mode.START, "the accessibility service connected")
    }

    /**
     * Whether the rider, not REau, has to restart a mute Android Auto server ([MuteServerFix]): always in the Lite
     * edition, and in the full one while the automatic switch is off or the service not connected. For the texts of
     * the "not responding" notice.
     */
    fun muteServerByRider(context: Context): Boolean =
        MuteServerFix.byRider(com.reau.app.Edition.hasAccessibility, switchOn(context), driver != null)

    /** The rider's "Tentar de novo" on the main screen. */
    fun retry(context: Context) {
        cancelledAtTapMs = 0
        RideDiagnostics.log("ui", "the rider asked to start Android Auto's server again")
        walk(context, ServerMenuRun.Mode.START, "the rider asked")
    }

    /** Whether REau's accessibility service is connected, switched on but not connected, or off. */
    fun accessibility(context: Context): Accessibility {
        if (driver != null) return Accessibility.CONNECTED
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return Accessibility.OFF
        val mine = ComponentName(context, AaServerAccessibilityService::class.java)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo?.serviceInfo?.let { info -> ComponentName(info.packageName, info.name) } == mine
        }
        return if (enabled) Accessibility.ENABLED else Accessibility.OFF
    }

    /** Android's page for REau's accessibility service (API 31+), or the list of every one. */
    fun openAccessibilitySettings(context: Context) {
        // The Lite edition has no accessibility service: there is nothing of REau's in that page to open.
        if (!com.reau.app.Edition.hasAccessibility) return
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val details = Intent(ACCESSIBILITY_DETAILS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, AaServerAccessibilityService::class.java).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(details) }.isSuccess) return
        }
        try {
            context.startActivity(list)
        } catch (e: ActivityNotFoundException) {
            log("AaServerStarter: no accessibility settings to open (${e.message})")
        }
    }

    /** The texts to look for: Android Auto's own, in the phone's language, plus the fixed tables. */
    // Android Auto's resources are known only by name, and their numbers change with each version.
    @SuppressLint("DiscouragedApi")
    internal fun texts(context: Context): ServerTexts = try {
        val resources = context.packageManager.getResourcesForApplication(AndroidAuto.PACKAGE)
        ServerTexts.load { name ->
            resources.getIdentifier(name, "string", AndroidAuto.PACKAGE).takeIf { it != 0 }?.let { id -> runCatching { resources.getString(id) }.getOrNull() }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        ServerTexts.table()
    }

    // Never in the Lite edition: it has no accessibility service to walk Android Auto's menu.
    private fun switchOn(context: Context) = com.reau.app.Edition.hasAccessibility && SettingsStore.open(context).load().aaServerAuto

    private fun walkIfOn(context: Context, mode: ServerMenuRun.Mode, why: String) {
        if (switchOn(context)) walk(context, mode, why)
    }

    private fun walk(context: Context, mode: ServerMenuRun.Mode, why: String, leftOnIfRefused: Boolean = false) {
        val app = context.applicationContext
        main.post {
            if (SystemClock.elapsedRealtime() - cancelledAtTapMs < TAP_COVER_MS) {
                cancelledAtTapMs = 0
                result.value = if (leftOnIfRefused) ServerResult.LEFT_ON else ServerResult.INTERRUPTED
                return@post log("AaServerStarter: ${mode.name.lowercase()} not tried ($why): the rider cancelled at the tap")
            }
            val phone = ServerGate.Phone(driver != null, locked(app), settingsOpen(app))
            val refusal = ServerGate.refusal(phone)
            val running = driver
            if (refusal != null || running == null) {
                running?.dropCover()
                val said = refusal ?: ServerResult.SERVICE_OFF
                result.value = if (leftOnIfRefused) ServerResult.LEFT_ON else said
                val left = if (leftOnIfRefused) "; the server is left on" else ""
                return@post log("AaServerStarter: ${mode.name.lowercase()} not tried ($why): ${refusalLine(said)}$left")
            }
            val texts = texts(app)
            val begun = SystemClock.elapsedRealtime()
            val run = ServerMenuRun(mode, texts, begun)
            val what = "${mode.name.lowercase()} ($why) on Android Auto ${AndroidAuto.installedVersion(app) ?: "(not installed)"}"
            log("AaServerStarter: $what; texts from Android Auto: ${texts.fromAndroidAuto} of ${ServerTexts.RESOURCES.size}, the table for the rest")
            if (mode == ServerMenuRun.Mode.START) log("AaServerStarter: start walk begins ${sinceStopsLine(begun)}")
            if (!running.drive(run) { outcome -> finished(run, outcome, SystemClock.elapsedRealtime() - begun) }) {
                next = { walk(app, mode, why, leftOnIfRefused) }
                return@post log("AaServerStarter: a walk is already going on; this ${mode.name.lowercase()} comes right after it")
            }
            result.value = ServerResult.working(mode)
            if (mode == ServerMenuRun.Mode.START) portWait.walkBegun()
        }
    }

    private fun finished(run: ServerMenuRun, outcome: ServerMenuRun.Outcome, tookMs: Long) {
        next?.let { waiting ->
            next = null
            main.post(waiting)
        }
        val menu = if (run.lastMenu.isEmpty()) "" else " (menu: ${run.lastMenu.joinToString(" | ")})"
        val restart = if (run.restarted) ", restarted" else ""
        log("AaServerStarter: ${run.mode.name.lowercase()} walk ended: $outcome in $tookMs ms$restart$menu")
        result.value = ServerResult.of(outcome)
        if (run.mode == ServerMenuRun.Mode.STOP && outcome == ServerMenuRun.Outcome.TAPPED_STOP) lastStopTapMs = SystemClock.elapsedRealtime()
        if (outcome != ServerMenuRun.Outcome.TAPPED_START) return portWait.walkEnded()
        val now = SystemClock.elapsedRealtime()
        if (portWait.tapped(now)) {
            result.value = ServerResult.STARTED
            return log("AaServerStarter: server answering already during the walk")
        }
        dialSoon?.invoke()
        main.postDelayed({
            if (portWait.expired(now)) {
                result.value = ServerResult.NOT_UP
                log("AaServerStarter: tapped start, port still closed after ${PORT_WAIT_MS / 1_000} s")
            }
        }, PORT_WAIT_MS)
    }

    /** How long before [nowMs] the server's "Stop" was last touched and the last projection ended, for the log. */
    private fun sinceStopsLine(nowMs: Long): String {
        fun since(at: Long) = if (at == 0L) "never in this process" else "${nowMs - at} ms ago"
        return "(last Stop touched ${since(lastStopTapMs)}, last projection end ${since(lastProjectionEndMs)})"
    }

    private fun answered() {
        val took = portWait.answered(SystemClock.elapsedRealtime()) ?: return
        log("AaServerStarter: server answering $took ms after the walk")
        result.value = ServerResult.STARTED
    }

    private fun locked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }

    private fun refusalLine(result: ServerResult): String = when (result) {
        ServerResult.SERVICE_OFF -> "accessibility service not connected"
        ServerResult.LOCKED -> "phone locked, not trying"
        ServerResult.NOT_INSTALLED -> "Android Auto's settings cannot be opened"
        else -> result.name
    }

    private fun log(line: String) {
        RideDiagnostics.log(TAG, line)
    }
}
