package com.reau.app

import android.app.Application
import com.reau.app.dash.DashWifiSuggestions
import com.reau.app.data.CrashHandler
import com.reau.app.data.DetailedLog
import com.reau.app.data.OwnLogcat
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.SystemEvents
import com.reau.app.settings.SettingsStore

/** The app's process: the ride log's folder and the crash handler are set before anything else runs. */
class REauApp : Application() {
    override fun onCreate() = com.reau.app.util.StartupTiming.timed("Application.onCreate") {
        super.onCreate()
        create()
    }

    private fun create() {
        // Privacy: the logs and recordings in internal storage; an older version's are moved there,
        // off the main thread (recordings may be large). Before init, so the folder the log opens is the new one.
        kotlin.concurrent.thread(name = "folders") {
            val moved = com.reau.app.data.AppFolders.migrate(this)
            if (moved > 0) RideDiagnostics.log("diag", "moved $moved files from the old external folder to the app's own")
            // The recordings under their own ceiling when the app opens (the recorders do it again at each file).
            com.reau.app.data.RecordingsSpace.makeRoom(com.reau.app.data.AppFolders.recordings(this, "video"))?.let { RideDiagnostics.log("diag", it) }
        }
        RideDiagnostics.init(this)
        // The experimental guard of the start: with it on, a process that ended right after a start made with experiments leaves them off.
        // Before the settings are read, so that this run and the screens see them as they are now. Off (the default), nothing is done.
        val guardLine = runCatching {
            com.reau.app.settings.ExperimentsGuard.atProcessStart(SettingsStore.open(this), com.reau.app.settings.ExperimentsGuard.mark(filesDir))
        }.getOrNull()
        val settings = SettingsStore.open(this).load()
        // The log as the rider left it (on by default; off, nothing is written).
        RideDiagnostics.enabled = settings.rideLog
        // The browser server is off by default; a new process has no server, so a switch left on is turned off.
        com.reau.app.web.WebSwitch.atStart(SettingsStore.open(this)) { RideDiagnostics.log("app", it) }
        // Privacy: what is older than the rider's choice of days goes when the log file opens.
        RideDiagnostics.keepDays = settings.logKeepDays
        // One log for as long as the process lives; the projection marks itself inside it.
        RideDiagnostics.startProcess("app")
        // What the rider had set when the app started (the projection says it again with each start).
        RideDiagnostics.log("settings", com.reau.app.settings.SettingsInUse.line(settings.sanitized()))
        com.reau.app.settings.SettingsInUse.experimentsLine(settings)?.let { RideDiagnostics.log("settings", it) }
        guardLine?.let { RideDiagnostics.warn("settings", it) }
        // The app's own logcat to diag/logcat-*.txt while it lives.
        OwnLogcat.start(this)
        // The phone's events (screen, doze, saver, charger, networks, background) to the log.
        SystemEvents.start(this)
        CrashHandler.install(this)
        // The debug build tells the Closeable and the receiver that a screen leaves to the garbage collector (`Cleared Reference was only
        // reachable from finalizer`, 10:59:56 and 12:00:30 on 20/10/2026), to find which one.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build(),
            )
        }
        // The detailed log as the rider left it (on by default in a debug build, off in a release).
        DetailedLog.enabled = settings.detailedLog
        // What the dash told REau the last times, for the screen "Dados da moto" (decrypts, so off the main thread).
        kotlin.concurrent.thread(name = "dash-readings") {
            com.reau.app.dash.DashReadings.attach(com.reau.app.dash.DashReadingsStore.open(this))
        }
        // The dash's name and password decrypted here, so the main screen finds them ready (its first reads were on the main thread).
        kotlin.concurrent.thread(name = "dash-pairing") { runCatching { com.reau.app.dash.DashPairing.open(this).warm() } }
        // How the process before this one ended (`exit` line): at every opening of the app, not only when a projection starts. Off the main thread
        // (it asks Android for the exit history and may keep a trace); the main process only (the debug build's test processes are not the app's).
        // The projection's start asks again and tells only what came after: the book of what was told is on disk (ExitHistory).
        if (Application.getProcessName() == packageName) {
            kotlin.concurrent.thread(name = "exit-history") { runCatching { com.reau.app.data.ExitHistory.reportPrevious(this) } }
        }
        // The bike Wi-Fi suggestion, in step with the switch and the dash's name (Android keeps it otherwise).
        // (off the main thread: binder calls to the Wi-Fi service)
        DashWifiSuggestions.syncSoon(this)
    }
}
