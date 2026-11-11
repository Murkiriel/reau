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
import com.reau.app.settings.ExperimentsGuard
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsInUse
import com.reau.app.util.DiagnosticsExport
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

    /**
     * The rider's mark [mark]: the session recording of Android Auto, if one is being made, is copied to a name its ring does not
     * overwrite (`MarkFreezer`). Returns at once. False: nothing to keep.
     */
    fun freezeRecording(mark: Int): Boolean = false

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

        /** What a command found, for the log: a Desligar that begins, or a Ligar that comes while that stop is still carried out. */
        internal enum class TapNote { NONE, STOP_TAPPED, LIGAR_DURING_STOP }

        internal fun tapNote(action: String?, phase: Phase, stopTappedAtMs: Long): TapNote = when {
            action == ACTION_STOP && phase != Phase.STOPPED -> TapNote.STOP_TAPPED
            action == ACTION_START && stopTappedAtMs != 0L -> TapNote.LIGAR_DURING_STOP
            else -> TapNote.NONE
        }

        /**
         * The foreground types the projection asks for beyond the connected device: [microphone] for Gemini, [location] for the dash's Wi-Fi
         * list (Android gives an app no list while it is behind another one, unless its foreground service carries the location type).
         * [gps]: the info ring's GPS card would use that type too (only for the log's reason).
         */
        internal class ForegroundNeeds(val microphone: Boolean, val location: Boolean, val gps: Boolean = false)

        /**
         * Gemini on and the permission to record. The location type is asked for whenever the precise location permission is given, whatever the
         * ring, its GPS card or the rider's "Usar a localização": it is what lets REau read the dash's network in the Wi-Fi list with the app
         * behind another one, and it makes REau read no position (the GPS and the weather have their own switches).
         */
        internal fun foregroundNeeds(settings: REauSettings, recordAudioGranted: Boolean, fineLocationGranted: Boolean) = ForegroundNeeds(
            microphone = settings.gemini && recordAudioGranted,
            location = fineLocationGranted,
            gps = settings.infoRing && settings.useLocation &&
                com.reau.app.weather.RingData.shown(settings.infoRingData, com.reau.app.weather.RingData.GPS) && fineLocationGranted,
        )

        /**
         * The log lines of a foreground start that Android accepted, once per start: the types it accepted, why each extra was asked for, and the
         * warning when the location type was wanted and is not there (the Wi-Fi list will be empty behind another app). No network names.
         */
        internal fun foregroundNotes(accepted: Int, needs: ForegroundNeeds, fineLocationGranted: Boolean): List<String> = buildList {
            add("projection: foreground types accepted: ${ForegroundTypes.names(accepted)} (0x${Integer.toHexString(accepted)})")
            if (accepted and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) add("projection: foreground with the microphone, for Gemini")
            val location = accepted and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
            if (location) {
                add("projection: foreground with the location, for the dash's Wi-Fi list" + if (needs.gps) " and the info ring's GPS" else "")
            } else if (!fineLocationGranted) {
                add("${RideDiagnostics.WARN}projection: no precise location permission: the Wi-Fi list will be empty while REau is behind another app")
            } else {
                add("${RideDiagnostics.WARN}projection: the location type was not accepted: the Wi-Fi list will be empty while REau is behind another app")
            }
        }

        /** A start builds its sides when the projection is stopped (a start on a running one keeps what it has). */
        internal fun buildsSides(sidesFor: Boolean?, phase: Phase): Boolean = sidesFor != null && phase == Phase.STOPPED

        /** A mark of the rider's goes in a projection that is on; a refit applies only to one that is running. */
        internal fun markApplies(phase: Phase): Boolean = phase != Phase.STOPPED

        internal fun refitApplies(phase: Phase): Boolean = phase == Phase.RUNNING

        /** A drop of Android Auto's side, for the summary. */
        internal fun isAaDrop(event: ProjectionEvent): Boolean = event is ProjectionEvent.AndroidAutoChanged && event.state == AaLinkState.DROPPED

        private const val STOP_WAIT_MS = 5_000L
        private const val MS_PER_MINUTE = 60_000L
        private const val NOTIFICATION_PACE_MS = 250L

        /** How often the phone's state is looked at, and how often a line is written with no relevant change. */
        private const val DEVICE_LOOK_MS = 5_000L
        private const val DEVICE_PERIOD_MS = 30_000L

        /** Test-only: the locks and the earlier runs' reports take this much longer, as they once did on the phone. */
        @Volatile internal var testOnlyHousekeepingMs = 0L

        private val current = MutableStateFlow(Projection())

        /** Which service instance may write [current] and [started]: an old one still releasing does not overwrite the new one's (A-20, [StateOwner]). */
        private val owner = StateOwner()

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

        /** The foreground types Android accepted for the service now in the foreground (`Service.foregroundServiceType`); 0 when it is not. */
        @Volatile var acceptedTypes = 0
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

    // This instance's number for [owner]; the writes of the process-wide state go through it.
    private val instance = owner.newInstance()
    private fun publish(write: () -> Unit) = owner.publish(instance, write)

    private lateinit var worker: ScheduledExecutorService
    private lateinit var controller: ProjectionController

    // The sides of the kind last switched on, built at that start; only the worker reads and writes these three.
    private var sides: ProjectionSides? = null
    private var sidesForCard = false
    private var sidesBuilt = 0

    /** Why the sides of this start could not be built, until [Ports.prepare] tells it; the worker only. */
    private var sidesFailure: String? = null
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
            { headline -> ProjectionNotification.show(this, headline, markNote(), started.value, card = controller.state.card) },
        )
        // The settings are decoded once (the Keystore, the file) and kept; read here, on the worker, the tap on Ligar finds
        // them warm when goForeground() reads them on the main thread.
        worker.execute { runCatching { SettingsStore.open(this).load() } }
        controller = ProjectionController(
            Ports(), ::log,
            onNoDashStop = { minutes -> ProjectionNotification.showStoppedNoDash(this, minutes) },
            noDashStopMs = { SettingsStore.open(this).load().noDashStopMinutes * MS_PER_MINUTE },
            onChange = { state ->
                publish {
                    countDash(current.value.dash, state.dash)
                    current.value = state
                }
                // "Desligando" goes up at once: the stop blocks this worker, which the pacer's wait runs on. Not when a newer start
                // already holds the foreground (its notification is the one to keep).
                if (state.headline == Headline.STOPPING && ForegroundHandover.dropsForeground(command, lastStart)) {
                    pacer.showNow(Headline.STOPPING)
                } else {
                    pacer.offer(state.headline)
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        com.reau.app.util.StartupTiming.timed("ProjectionService.onStartCommand") { startCommand(intent, startId) }

    private fun startCommand(intent: Intent?, startId: Int): Int {
        val action = intent?.action
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
                if (!markApplies(controller.state.phase)) {
                    stopSelf(startId)
                } else {
                    RideDiagnostics.log("mark", marks.mark(System.currentTimeMillis()))
                    // The ring of the session recording overwrites itself (about 11 min of a long ride): the mark keeps a copy.
                    runCatching { sides?.freezeRecording(marks.count) }
                        .onFailure { log("${RideDiagnostics.WARN}mark ${marks.count}: keeping the session recording failed: $it") }
                    ProjectionNotification.show(this, controller.state.headline, markNote(), started.value, card = controller.state.card)
                }
            }
            ACTION_REFIT -> worker.execute {
                if (refitApplies(controller.state.phase)) {
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
        // The system stopping the service is a switch-off too. The stop, and what follows it, run off the main thread: waiting here for the
        // worker (up to 5 s) froze the screen whenever the system destroyed the service during a slow stop (audit of 26/10/2026).
        val stopping = runCatching { worker.submit { controller.submit(ProjectionEvent.Stop) } }
        val begun = SystemClock.elapsedRealtime()
        BackgroundRelease.run("projection-release", { log("${RideDiagnostics.WARN}projection: releasing in onDestroy failed: $it") }) {
            ReleaseSteps.runAll(
                listOf<Pair<String, () -> Unit>>(
                    "stop" to { stopping.getOrThrow().get(STOP_WAIT_MS, TimeUnit.MILLISECONDS) },
                    // The sides (compositor, codecs, Wi-Fi) are let go here too, off the main thread (BackgroundRelease): once the worker is
                    // gone nothing else touches them; a next start builds its own.
                    "worker" to { worker.shutdownNow() },
                    "sides" to {
                        val leaving = sides
                        sides = null
                        leaving?.release()
                        log("projection: the sides were let go in ${SystemClock.elapsedRealtime() - begun} ms, off the main thread")
                    },
                    "locks" to { locks.release() },
                    // Also a stop left half-way ("Desligando"): the screen never stays on it once the service is gone. What went wrong with a
                    // start that was refused (no compositor, no sides) stays to be read: the service leaves at once, and the reason would go with it.
                    "state" to {
                        val reason = current.value.problem
                        publish { if (current.value != Projection(problem = reason)) current.value = Projection(problem = reason) }
                    },
                ),
            ) { name, e -> log("${RideDiagnostics.WARN}projection: in onDestroy, $name failed: ${e.javaClass.simpleName}") }
            log("projection: service gone, locks released")
        }
        inForeground = false
        acceptedTypes = 0
        super.onDestroy()
    }

    /** For the log: a Ligar that finds the last Desligar still being carried out (it waits for it), and when that Desligar began. */
    private fun noteTapDuringStop(action: String?) {
        val now = SystemClock.elapsedRealtime()
        when (tapNote(action, current.value.phase, stopTappedAtMs)) {
            TapNote.STOP_TAPPED -> stopTappedAtMs = now
            TapNote.LIGAR_DURING_STOP ->
                log("projection: Ligar tapped ${now - stopTappedAtMs} ms after Desligar, while that stop is still being carried out: the start waits behind it")
            TapNote.NONE -> Unit
        }
    }

    /**
     * The machine takes [event] on the worker. [commandId] is the start id of the command that brought it: the
     * service is stopped with it, so a stop never ends a service that has meanwhile been asked to start again.
     */
    private fun handle(commandId: Int, event: ProjectionEvent, sidesFor: Boolean? = null) = worker.execute {
        command = commandId
        if (sidesFor != null && buildsSides(sidesFor, controller.state.phase)) buildSides(card = sidesFor)
        controller.submit(event)
        if (event == ProjectionEvent.Stop && stopTappedAtMs != 0L) {
            log("projection: the stop was carried out ${SystemClock.elapsedRealtime() - stopTappedAtMs} ms after Desligar was tapped")
            stopTappedAtMs = 0L
        }
        if (controller.state.phase == Phase.STOPPED) stopSelf(commandId)
    }

    /**
     * [sidesOfKind], with a failure kept to be told: the worker swallows what a task throws, and the service would stay in the
     * foreground on "Preparando" with no sides and no word. [Ports.prepare] refuses the start with this text instead.
     */
    @Suppress("TooGenericExceptionCaught") // whatever a factory throws is the start's problem, said to the rider and the log
    private fun buildSides(card: Boolean) {
        sidesFailure = try {
            sidesOfKind(card)
            null
        } catch (e: Exception) {
            log("${RideDiagnostics.ERROR}projection: the sides could not be built: ${e.javaClass.simpleName}")
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Makes sure the sides are of the kind about to be switched on: the card's or Android Auto's. Sides of the other
     * kind are let go first; whatever they report afterwards no longer reaches the machine.
     */
    private fun sidesOfKind(card: Boolean) {
        if (sides != null && sidesForCard == card) return
        sides?.let { old -> runCatching { old.release() }.onFailure { log("projection: releasing the sides failed: $it") } }
        sides = null // a factory that throws must not leave the sides just released in place
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
        // Gemini switched on and the permission to record given: the microphone type. The precise location permission given: the location type,
        // for the dash's Wi-Fi list with the app behind another one (and the info ring's GPS), whatever the ring or "Usar a localização".
        val fineLocationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val needs = foregroundNeeds(
            SettingsStore.open(this).load(),
            recordAudioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            fineLocationGranted = fineLocationGranted,
        )
        for (types in ForegroundTypes.ladder(microphone = needs.microphone, location = needs.location)) {
            val refusal = try {
                ProjectionNotification.createChannel(this)
                ProjectionNotification.refreshAlertChannel(this)
                startForeground(ProjectionNotification.ID, ProjectionNotification.build(this, Headline.PREPARING), types)
                inForeground = true
                worker.execute { pacer.alreadyShown(Headline.PREPARING) }
                // What Android really accepted, not what was asked for (minSdk 29 has foregroundServiceType).
                acceptedTypes = foregroundServiceType
                foregroundNotes(acceptedTypes, needs, fineLocationGranted).forEach(::log)
                return true
            } catch (e: IllegalStateException) {
                e
            } catch (e: SecurityException) {
                e
            }
            why = "${refusal.javaClass.simpleName}: ${refusal.message}"
            log("${RideDiagnostics.ERROR}projection: foreground start refused for types $types — $why")
        }
        publish { current.value = Projection(problem = why) }
        return false
    }

    @Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
    private inner class Ports : ProjectionController.Ports {
        override fun prepare(): String? {
            // One ride log per projection, in <external files>/diag, where `adb pull` reaches it.
            RideDiagnostics.start("projection")
            val inUse = SettingsStore.open(this@ProjectionService).load().sanitized()
            RideDiagnostics.log("settings", SettingsInUse.line(inUse))
            SettingsInUse.experimentsLine(inUse)?.let { RideDiagnostics.log("settings", it) }
            watchTheStart(inUse)
            marks.reset()
            // A new summary for this projection, written when it is switched off.
            ProjectionSummary.current = RideSummary().apply { start(SystemClock.elapsedRealtime(), batteryPct = null) }
            publish { started.value = SystemClock.elapsedRealtime() }
            // Sides that could not be built, or with no compositor, cannot project; the start fails here, with the reason, and the
            // machine gives the service back (Release) instead of connecting a dash that would never get video.
            refusal()?.let { return it }
            // The earlier runs' reports and the locks (together once 0.4 to 0.8 s on a phone) follow Android Auto's side, so
            // the start is not slow. On this same worker, so they come before any stop asked for after this start.
            worker.execute { housekeeping() }
            return null
        }

        /** Why this start cannot go on, said to the rider; null when it can. */
        private fun refusal(): String? = when {
            sidesFailure != null -> sidesFailure.also {
                sidesFailure = null
                log("${RideDiagnostics.ERROR}projection: refused, the sides could not be built")
            }
            sides?.videoReady == false -> {
                log("${RideDiagnostics.ERROR}projection: refused, ${ProjectionSides.COMPOSITOR_NOT_STARTED}")
                getString(R.string.problem_compositor)
            }
            else -> null
        }

        /** What a start needs but Android Auto's first dial does not: the earlier runs' reports, the locks, the device look. */
        private fun housekeeping() {
            val begun = SystemClock.elapsedRealtime()
            if (testOnlyHousekeepingMs > 0) Thread.sleep(testOnlyHousekeepingMs)
            CrashHandler.reportPrevious(this@ProjectionService)
            ExitHistory.reportPrevious(this@ProjectionService)
            // the zip of the last share holds logs; it does not wait in the cache
            DiagnosticsExport.forgetZips(java.io.File(cacheDir, DiagnosticsExport.SHARED_DIR))
            val reported = SystemClock.elapsedRealtime()
            // Switched off before this came (Desligar right after Ligar): nothing to hold.
            if (controller.state.phase == Phase.STOPPED) return
            locks.acquire()
            val took = "earlier runs read in ${reported - begun} ms, locks in ${SystemClock.elapsedRealtime() - reported} ms"
            log("projection: foreground with wake and Wi-Fi locks ($took)")
            lookAtTheDevice()
        }

        // The mark this projection's start left for the guard, null when it left none; the worker only.
        private var guardToken: String? = null

        /** The experimental guard of the start ([ExperimentsGuard]): with it on and experiments on, a mark is left until the projection has run 10 s. */
        private fun watchTheStart(inUse: REauSettings) {
            val mark = ExperimentsGuard.mark(filesDir)
            val token = ExperimentsGuard.projectionStarted(inUse, mark, System.currentTimeMillis()) ?: return
            guardToken = token
            log("projection: ${ExperimentsGuard.watchingLine(ExperimentsGuard.experimentsOn(inUse))}")
            schedule(ExperimentsGuard.SETTLE_MS) { ExperimentsGuard.settled(mark, token) }
        }

        override fun release() {
            publish { started.value = null }
            // Switched off by the rider: whatever the start did, the app did not die of it.
            ExperimentsGuard.settled(ExperimentsGuard.mark(filesDir), guardToken)
            guardToken = null
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
            ProjectionNotification.clearPhoneHot(this@ProjectionService)
            RideDiagnostics.log(
                "projection",
                ProjectionSummary.current.line(
                    SystemClock.elapsedRealtime(), marks.count, noImageMs = com.reau.app.dash.ImageGap.clock.totalMs(com.reau.app.util.MonotonicClock.nowMs()),
                ),
            )
            deviceLook = null
            locks.release()
            // A start that came while this stop was carried out has taken the foreground; it stays.
            if (ForegroundHandover.dropsForeground(command, lastStart)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
                acceptedTypes = 0
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
        // Armed for this projection: one warning when the battery passes 44 C, another only after it fell under 41 C (BatteryHeatWatch).
        val heat = BatteryHeatWatch()
        // Said once for this projection: the reader does not ask for the thermal headroom in this build (the lines have no headroom=).
        RideDiagnostics.log("device", com.reau.app.data.ThermalHeadroomNote.LINE)
        deviceLook?.cancel(false)
        deviceLook = worker.scheduleWithFixedDelay({
            runCatching {
                val state = reader.read()
                device.offer(SystemClock.elapsedRealtime(), state)
                ProjectionSummary.current.device(state.thermal, state.batteryPct)
                warnIfHot(heat, state)
            }
                .onFailure { log("${RideDiagnostics.WARN}projection: reading the device state failed: $it") }
        }, 0, DEVICE_LOOK_MS, TimeUnit.MILLISECONDS)
    }

    /** The rider's notice that the phone is hot ([BatteryHeatWatch]), and its line in the ride log; the notice goes when the phone has cooled. */
    private fun warnIfHot(heat: BatteryHeatWatch, state: com.reau.app.data.DeviceState) {
        val temp = state.batteryTempC
        val text = temp?.let { String.format(java.util.Locale.US, "%.1f", it) }
        when (heat.offer(temp)) {
            BatteryHeatWatch.Verdict.WARN -> {
                RideDiagnostics.warn(
                    "heat",
                    "battery at $text C (charging=${state.charging}, thermal=${state.thermal}): " +
                        "at or over ${BatteryHeatWatch.HOT_C} C, rider told the phone is hot",
                )
                ProjectionNotification.showPhoneHot(this, Math.round(temp ?: BatteryHeatWatch.HOT_C))
            }
            BatteryHeatWatch.Verdict.COOLED -> {
                RideDiagnostics.log("heat", "battery back under ${BatteryHeatWatch.COOL_C} C ($text C): a new warning is possible")
                ProjectionNotification.clearPhoneHot(this)
            }
            BatteryHeatWatch.Verdict.NONE -> Unit
        }
    }

    /** A drop of Android Auto's side, for the summary. */
    private fun countDrop(event: ProjectionEvent) {
        if (isAaDrop(event)) ProjectionSummary.current.aaDropped()
    }

    /** The dash side went from [before] to [after], for the summary: a drop, a failed try, or nothing to count. */
    private fun countDash(before: DashLink, after: DashLink) {
        when (DashDrops.of(before, after)) {
            DashDrops.Kind.DROP -> ProjectionSummary.current.dashDropped(SystemClock.elapsedRealtime())
            DashDrops.Kind.FAILED_TRY -> ProjectionSummary.current.dashTryFailed()
            DashDrops.Kind.NONE -> Unit
        }
    }

    private fun log(line: String) = RideDiagnostics.log("projection", line)
}
