// A foreground service of the "connected device" type holding a wake lock and a Wi-Fi lock (ProjectionLocks),
// startForeground() on every start request (Android kills the app when one goes unanswered), the low-importance
// notification channel (ProjectionNotification), and the clean fall: a refused foreground start costs the projection,
// never the app. The ladder of types is there for Gemini's microphone (ForegroundTypes).
package com.reau.app.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.IBinder
import android.os.SystemClock
import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.data.CrashHandler
import com.reau.app.data.DeviceStateLog
import com.reau.app.data.ProjectionSummary
import com.reau.app.data.RideSummary
import com.reau.app.data.ExitHistory
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsInUse
import com.reau.app.settings.SettingsStore
import com.reau.app.video.FrameFit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The two sides and the video, as the service drives them. The real ones are [LiveSides]. */
interface ProjectionSides {
    fun startAndroidAuto()
    fun stopAndroidAuto()
    fun startDash()
    fun stopDash()
    fun startVideo()
    fun stopVideo()

    /** The service is going: everything the sides hold is let go. */
    fun release() = Unit

    /** A new fit for the picture while running (adjusted in the settings, looking at the dash). */
    fun remap(mapping: FrameFit.Mapping) = Unit

    /**
     * The rider's settings changed while running (every video setting applies at once,
     * [LiveTuning]). Without more to do, the new fit.
     */
    fun retune(settings: REauSettings) = remap(settings.mapping)

    /** The bike's Wi-Fi while the sides hold it, for the `device` line; null when none. */
    val bikeNetwork: Network? get() = null

    /** The last try for the dash found its Wi-Fi in the scan but was refused: it is there, stuck or busy. */
    val dashVisible: Boolean get() = false

    /**
     * Whether these sides can make video for the dash at all. False when the compositor
     * (OpenGL) did not start: then the projection is refused at its preparation, with `problem_compositor` as
     * the problem (the log says [COMPOSITOR_NOT_STARTED]), instead of running on to a dash session that would read as streaming with nothing to stream.
     */
    val videoReady: Boolean get() = true

    companion object {
        /** Why the sides have no compositor, for the log (the rider reads it in the rider's language: `problem_compositor`). */
        const val COMPOSITOR_NOT_STARTED = "the video compositor (OpenGL) did not start: no picture for the dash"
    }

    /** Nothing is started: the service, its locks and its machine run on their own. For tests. */
    object None : ProjectionSides {
        override fun startAndroidAuto() = Unit
        override fun stopAndroidAuto() = Unit
        override fun startDash() = Unit
        override fun stopDash() = Unit
        override fun startVideo() = Unit
        override fun stopVideo() = Unit
    }
}

/**
 * The projection, while the rider has it on: a foreground service that keeps the process, the CPU and the Wi-Fi
 * awake with the screen off, and runs [ProjectionMachine] through a [ProjectionController] on a thread of its own
 * (never the main thread: stopping Android Auto's receiver may wait a few seconds).
 *
 * [start] and [stop] are all the screen needs; [projection] is where things stand, for the screen and the
 * notification. Nothing starts by itself: not after a reboot, not when the process is killed.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
class ProjectionService : Service() {
    companion object {
        const val ACTION_START = "com.reau.app.PROJECTION_START"
        const val ACTION_STOP = "com.reau.app.PROJECTION_STOP"
        const val ACTION_START_CARD = "com.reau.app.PROJECTION_START_CARD"
        const val ACTION_REFIT = "com.reau.app.PROJECTION_REFIT"
        const val ACTION_MARK = "com.reau.app.PROJECTION_MARK"

        /** The tag of both locks, as `dumpsys power` and `dumpsys wifi` show it. */
        const val LOCK_TAG = "reau:projection"

        private const val STOP_WAIT_MS = 5_000L
        private const val NOTIFICATION_PACE_MS = 250L

        /** How often the phone's state is looked at, and how often a line is written with no relevant change. */
        private const val DEVICE_LOOK_MS = 5_000L
        private const val DEVICE_PERIOD_MS = 30_000L

        /** Test-only: the locks and the earlier runs' reports take this much longer, as they once did on the phone. */
        @Volatile internal var testOnlyHousekeepingMs = 0L

        private val current = MutableStateFlow(Projection())

        /** Where the projection stands. */
        val projection: StateFlow<Projection> = current.asStateFlow()

        private val started = MutableStateFlow<Long?>(null)

        /**
         * When the projection now on started (SystemClock.elapsedRealtime), for the time on the main screen's Desligar;
         * null while it is off.
         */
        val startedAt: StateFlow<Long?> = started.asStateFlow()

        /** Whether the service holds the foreground now (the `app background` line says it). */
        @Volatile var inForeground = false
            private set

        /** Builds the sides for a service; it gets the channel back to the machine. Tests replace it. */
        @Volatile internal var sidesFactory: (Context, (ProjectionEvent) -> Unit) -> ProjectionSides =
            { context, report -> LiveSides(context, report, LiveSides.Setup.forPhone(context)) }

        /** Builds the sides of the card mode: the card in place of Android Auto. Tests replace it. */
        @Volatile internal var cardSidesFactory: (Context, (ProjectionEvent) -> Unit) -> ProjectionSides =
            { context, report -> LiveSides(context, report, LiveSides.Setup.forCard(context)) }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_START))
        }

        /** Switches on the card mode: a still picture to the dash, no Android Auto. */
        fun startCard(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_START_CARD))
        }

        /**
         * Always sent, even when [projection] still says stopped: a start asked for a moment ago may not have reached
         * the service yet, and this stop must come after it. A service that was not running stops again at once.
         */
        /**
         * The fit in the settings changed: a running projection takes it at once. Sent only while running,
         * so it never starts the service; a projection started later reads the settings anyway.
         */
        fun refit(context: Context) {
            if (current.value.phase != Phase.RUNNING) return
            try {
                context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_REFIT))
            } catch (e: IllegalStateException) {
                RideDiagnostics.log("projection", "refit not sent: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // Android refuses to start a service from the background: then there is none to stop.
                RideDiagnostics.log("projection", "stop not sent: ${e.message}")
            }
        }
    }

    private lateinit var worker: ScheduledExecutorService
    private lateinit var controller: ProjectionController

    // The sides of the kind last switched on, built at that start; only the worker reads and writes these three.
    private var sides: ProjectionSides? = null
    private var sidesForCard = false
    private var sidesBuilt = 0
    private val locks = ProjectionLocks(this)

    // The start id of the command the worker is carrying out; only the worker reads and writes it.
    private var command = 0

    // The start id of the last start that took the foreground; written on the main thread, read by the worker.
    @Volatile private var lastStart = 0

    // When the rider's Desligar reached the service, until its effects are done (0: none going on). The stop is carried
    // out on the one worker and may take seconds (the receiver's bye); a Ligar tapped meanwhile waits behind it, with
    // the screen already saying "parado". Only for the log, to see that wait.
    @Volatile private var stopTappedAtMs = 0L

    // Used from the worker only.
    private lateinit var pacer: HeadlinePacer

    // The rider's marks of this projection, from the notification's button.
    private val marks = RiderMarks()

    /** What the notification says after a mark (the service's context: the language the rider chose); null before the first. */
    private fun markNote(): String? = marks.markedAt?.let { getString(R.string.notif_mark_done, marks.count, it) }

    // The phone's state looked at while the projection is on; only the worker reads and writes it.
    private var deviceLook: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Before Android 13 the chosen language is put on the context here (from 13 the system holds it).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.reau.app.settings.AppLanguages.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        RideDiagnostics.init(this)
        worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "projection") }
        pacer = HeadlinePacer(
            NOTIFICATION_PACE_MS,
            { delayMs, action ->
                val pending = worker.schedule(action, delayMs, TimeUnit.MILLISECONDS)
                ({ pending.cancel(false) })
            },
            { headline -> ProjectionNotification.show(this, headline, markNote(), started.value) },
        )
        controller = ProjectionController(Ports(), ::log, onChange = { state ->
            countDash(current.value.dash, state.dash)
            current.value = state
            // "Desligando" goes up at once: the stop blocks this worker, which the pacer's wait runs on. Not when a newer start
            // already holds the foreground (its notification is the one to keep).
            if (state.headline == Headline.STOPPING && ForegroundHandover.dropsForeground(command, lastStart)) {
                pacer.showNow(Headline.STOPPING)
            } else {
                pacer.offer(state.headline)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The server's cover goes up at the tap, not after the first dial or the session's end.
        val action = intent?.action
        if (action == ACTION_START || action == ACTION_STOP) AaServerStarter.riderSwitched(this, on = action == ACTION_START)
        noteTapDuringStop(action)
        when (action) {
            ACTION_START, ACTION_START_CARD -> if (goForeground()) {
                lastStart = startId
                val card = intent.action == ACTION_START_CARD
                handle(startId, if (card) ProjectionEvent.StartCard else ProjectionEvent.Start, sidesFor = card)
            } else {
                stopSelf(startId)
            }
            ACTION_STOP -> handle(startId, ProjectionEvent.Stop)
            ACTION_MARK -> worker.execute {
                if (controller.state.phase == Phase.STOPPED) {
                    stopSelf(startId)
                } else {
                    RideDiagnostics.log("mark", marks.mark(System.currentTimeMillis()))
                    ProjectionNotification.show(this, controller.state.headline, markNote(), started.value)
                }
            }
            ACTION_REFIT -> worker.execute {
                if (controller.state.phase == Phase.RUNNING) {
                    val settings = SettingsStore.open(this).load()
                    log("projection: refit from the settings: ${settings.mapping}")
                    runCatching { sides?.retune(settings) }.onFailure { log("${RideDiagnostics.WARN}projection: refit failed: $it") }
                } else {
                    stopSelf(startId)
                }
            }
            // A restart by the system (no intent): the projection is not resumed by itself.
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The system stopping the service is a switch-off too; done here, before the worker goes.
        runCatching { worker.submit { controller.submit(ProjectionEvent.Stop) }.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { log("${RideDiagnostics.WARN}projection: stopping in onDestroy failed: $it") }
        worker.shutdownNow()
        runCatching { sides?.release() }.onFailure { log("projection: releasing the sides failed: $it") }
        locks.release()
        // Also a stop left half-way ("Desligando"): the screen never stays on it once the service is gone.
        if (current.value != Projection()) current.value = Projection()
        log("projection: service gone, locks released")
        inForeground = false
        super.onDestroy()
    }

    /** For the log: a Ligar that finds the last Desligar still being carried out (it waits for it), and when that Desligar began. */
    private fun noteTapDuringStop(action: String?) {
        val now = SystemClock.elapsedRealtime()
        when {
            action == ACTION_STOP && current.value.phase != Phase.STOPPED -> stopTappedAtMs = now
            action == ACTION_START && stopTappedAtMs != 0L ->
                log("projection: Ligar tapped ${now - stopTappedAtMs} ms after Desligar, while that stop is still being carried out: the start waits behind it")
        }
    }

    /**
     * The machine takes [event] on the worker. [commandId] is the start id of the command that brought it: the
     * service is stopped with it, so a stop never ends a service that has meanwhile been asked to start again.
     */
    private fun handle(commandId: Int, event: ProjectionEvent, sidesFor: Boolean? = null) = worker.execute {
        command = commandId
        if (sidesFor != null && controller.state.phase == Phase.STOPPED) sidesOfKind(card = sidesFor)
        controller.submit(event)
        if (event == ProjectionEvent.Stop && stopTappedAtMs != 0L) {
            log("projection: the stop was carried out ${SystemClock.elapsedRealtime() - stopTappedAtMs} ms after Desligar was tapped")
            stopTappedAtMs = 0L
        }
        if (controller.state.phase == Phase.STOPPED) stopSelf(commandId)
    }

    /**
     * Makes sure the sides are of the kind about to be switched on: the card's or Android Auto's. Sides of the other
     * kind are let go first; whatever they report afterwards no longer reaches the machine.
     */
    private fun sidesOfKind(card: Boolean) {
        if (sides != null && sidesForCard == card) return
        sides?.let { old -> runCatching { old.release() }.onFailure { log("projection: releasing the sides failed: $it") } }
        val built = ++sidesBuilt
        val report: (ProjectionEvent) -> Unit = { event ->
            countDrop(event) // for the summary
            // Sides may still speak after the service is gone (a receiver thread finishing): then nobody listens.
            try {
                worker.execute { if (built == sidesBuilt) controller.submit(event) }
            } catch (e: RejectedExecutionException) {
                log("projection: a report after the service went: $event (${e.message})")
            }
        }
        sides = (if (card) cardSidesFactory else sidesFactory)(this, report)
        sidesForCard = card
    }

    /**
     * Android expects startForeground() after every startForegroundService(). The types are tried richest first
     * ([ForegroundTypes]: the microphone too with Gemini); false if every one was refused.
     */
    private fun goForeground(): Boolean {
        var why = ""
        // Gemini switched on and the permission to record given.
        val gemini = SettingsStore.open(this).load().gemini &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        for (types in ForegroundTypes.ladder(microphone = gemini)) {
            val refusal = try {
                ProjectionNotification.createChannel(this)
                startForeground(ProjectionNotification.ID, ProjectionNotification.build(this, Headline.PREPARING), types)
                inForeground = true
                worker.execute { pacer.alreadyShown(Headline.PREPARING) }
                if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) log("projection: foreground with the microphone, for Gemini")
                return true
            } catch (e: IllegalStateException) {
                e
            } catch (e: SecurityException) {
                e
            }
            why = "${refusal.javaClass.simpleName}: ${refusal.message}"
            log("${RideDiagnostics.ERROR}projection: foreground start refused for types $types — $why")
        }
        current.value = Projection(problem = why)
        return false
    }

    @Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
    private inner class Ports : ProjectionController.Ports {
        override fun prepare(): String? {
            // One ride log per projection, in <external files>/diag, where `adb pull` reaches it.
            RideDiagnostics.start("projection")
            RideDiagnostics.log("settings", SettingsInUse.line(SettingsStore.open(this@ProjectionService).load().sanitized()))
            marks.reset()
            // A new summary for this projection, written when it is switched off.
            ProjectionSummary.current = RideSummary().apply { start(SystemClock.elapsedRealtime(), batteryPct = null) }
            started.value = SystemClock.elapsedRealtime()
            // Sides with no compositor cannot project; the start fails here, with the reason, and the
            // machine gives the service back (Release) instead of connecting a dash that would never get video.
            if (sides?.videoReady == false) {
                log("${RideDiagnostics.ERROR}projection: refused, ${ProjectionSides.COMPOSITOR_NOT_STARTED}")
                return getString(R.string.problem_compositor)
            }
            // So the server's cover is not slow to appear, the earlier runs' reports and the locks
            // (together once 0.4 to 0.8 s on a phone) follow Android Auto's side, whose first dial starts the walk
            // behind that cover. On this same worker, so they come before any stop asked for after this start.
            worker.execute { housekeeping() }
            return null
        }

        /** What a start needs but Android Auto's first dial does not: the earlier runs' reports, the locks, the device look. */
        private fun housekeeping() {
            val begun = SystemClock.elapsedRealtime()
            if (testOnlyHousekeepingMs > 0) Thread.sleep(testOnlyHousekeepingMs)
            CrashHandler.reportPrevious(this@ProjectionService)
            ExitHistory.reportPrevious(this@ProjectionService)
            val reported = SystemClock.elapsedRealtime()
            // Switched off before this came (Desligar right after Ligar): nothing to hold.
            if (controller.state.phase == Phase.STOPPED) return
            locks.acquire()
            val took = "earlier runs read in ${reported - begun} ms, locks in ${SystemClock.elapsedRealtime() - reported} ms"
            log("projection: foreground with wake and Wi-Fi locks ($took)")
            lookAtTheDevice()
        }

        override fun release() {
            started.value = null
            // The browser switch goes off with the projection and stays off until the rider turns it on again.
            // Not when a start came after this stop: that run is the rider's now (it turned the switch off itself when it began).
            runCatching {
                if (com.reau.app.web.WebSwitch.resetsAtStop(command, lastStart) &&
                    com.reau.app.web.WebSwitch.switchOffStored(com.reau.app.settings.SettingsStore.open(this@ProjectionService))
                ) {
                    log("projection: the browser switch went off with the projection")
                }
            }
            deviceLook?.cancel(false)
            RideDiagnostics.log("projection", ProjectionSummary.current.line(SystemClock.elapsedRealtime(), marks.count))
            deviceLook = null
            locks.release()
            // A start that came while this stop was carried out has taken the foreground; it stays.
            if (ForegroundHandover.dropsForeground(command, lastStart)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
            } else {
                log("projection: a start came after this stop: the foreground stays")
            }
            RideDiagnostics.stop("projection")
            stopSelf(command)
        }

        override fun startAndroidAuto() = sides?.startAndroidAuto() ?: Unit
        override fun stopAndroidAuto() = sides?.stopAndroidAuto() ?: Unit
        override fun startDash() = sides?.startDash() ?: Unit
        override fun stopDash() = sides?.stopDash() ?: Unit
        override fun startVideo() = sides?.startVideo() ?: Unit
        override fun stopVideo() = sides?.stopVideo() ?: Unit

        override fun dashVisible(): Boolean = sides?.dashVisible ?: false

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            if (worker.isShutdown) return {}
            val pending = worker.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            return { pending.cancel(false) }
        }
    }

    /**
     * The phone's state every [DEVICE_LOOK_MS]; [DeviceStateLog] writes the first look,
     * one every 30 s and every relevant change.
     */
    private fun lookAtTheDevice() {
        val reader = DeviceStateReader(this) { sides?.bikeNetwork }
        val device = DeviceStateLog(DEVICE_PERIOD_MS) { RideDiagnostics.log("device", it) }
        deviceLook?.cancel(false)
        deviceLook = worker.scheduleWithFixedDelay({
            runCatching {
                val state = reader.read()
                device.offer(SystemClock.elapsedRealtime(), state)
                ProjectionSummary.current.device(state.thermal, state.batteryPct)
            }
                .onFailure { log("${RideDiagnostics.WARN}projection: reading the device state failed: $it") }
        }, 0, DEVICE_LOOK_MS, TimeUnit.MILLISECONDS)
    }

    /** A drop of Android Auto's side, for the summary. */
    private fun countDrop(event: ProjectionEvent) {
        if (event is ProjectionEvent.AndroidAutoChanged && event.state == AaLinkState.DROPPED) ProjectionSummary.current.aaDropped()
    }

    /** The dash side went from [before] to [after], for the summary: a drop, a failed try, or nothing to count. */
    private fun countDash(before: DashLink, after: DashLink) {
        when (DashDrops.of(before, after)) {
            DashDrops.Kind.DROP -> ProjectionSummary.current.dashDropped()
            DashDrops.Kind.FAILED_TRY -> ProjectionSummary.current.dashTryFailed()
            DashDrops.Kind.NONE -> Unit
        }
    }

    private fun log(line: String) = RideDiagnostics.log("projection", line)
}
