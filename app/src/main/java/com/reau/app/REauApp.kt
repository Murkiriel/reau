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
    override fun onCreate() {
        super.onCreate()
        // Privacy: the logs and recordings in internal storage; an older version's are moved there,
        // off the main thread (recordings may be large). Before init, so the folder the log opens is the new one.
        kotlin.concurrent.thread(name = "folders") {
            val moved = com.reau.app.data.AppFolders.migrate(this)
            if (moved > 0) RideDiagnostics.log("diag", "moved $moved files from the old external folder to the app's own")
            // The recordings under their own ceiling when the app opens (the recorders do it again at each file).
            com.reau.app.data.RecordingsSpace.makeRoom(com.reau.app.data.AppFolders.recordings(this, "video"))?.let { RideDiagnostics.log("diag", it) }
        }
        RideDiagnostics.init(this)
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
        // The app's own logcat to diag/logcat-*.txt while it lives.
        OwnLogcat.start(this)
        // The phone's events (screen, doze, saver, charger, networks, background) to the log.
        SystemEvents.start(this)
        CrashHandler.install(this)
        // The detailed log as the rider left it (off by default).
        DetailedLog.enabled = settings.detailedLog
        // What the dash told REau the last times, for the screen "Dados da moto" (decrypts, so off the main thread).
        kotlin.concurrent.thread(name = "dash-readings") {
            com.reau.app.dash.DashReadings.attach(com.reau.app.dash.DashReadingsStore.open(this))
        }
        // The bike Wi-Fi suggestion, in step with the switch and the dash's name (Android keeps it otherwise).
        DashWifiSuggestions.sync(this)
    }
}
