package com.reau.app.data

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * Catches what would crash the app: before the process dies, the crash goes to a
 * `crash-….txt` ([CrashRecorder]) and a line to the open ride log, written at once; then Android's own handler runs
 * as it would have (the app still crashes). [reportPrevious] tells, once, the crash of an earlier run.
 */
object CrashHandler {
    /** How long a dying process waits for the ride log to have the crash line. */
    private const val LOG_WAIT_MS = 2_000L

    fun install(context: Context) {
        val recorder = recorder(context) ?: return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(recorder, previous))
    }

    /** Writes into the ride log the newest crash of an earlier run not yet told, and marks it told. */
    fun reportPrevious(context: Context) {
        val recorder = recorder(context) ?: return
        val file = recorder.unreported() ?: return
        // The fifth line of the file is the exception itself (after the title, time, thread and uptime).
        val what = runCatching { file.readLines().getOrNull(STACK_LINE) }.getOrNull().orEmpty()
        RideDiagnostics.error("error", "the app crashed last time: $what (${file.name})")
        recorder.markReported(file)
    }

    private fun recorder(context: Context): CrashRecorder? =
        CrashRecorder(AppFolders.diag(context)) // internal storage ([AppFolders])

    private class Handler(
        private val recorder: CrashRecorder,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        // Nothing here may stop Android's own handler from running: whatever goes wrong while recording is dropped.
        @Suppress("TooGenericExceptionCaught")
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val file = recorder.record(thread, error, SystemClock.uptimeMillis() - Process.getStartUptimeMillis())
                RideDiagnostics.logAndWait("error", "${RideDiagnostics.ERROR}the app crashed: ${CrashRecorder.summary(error)} (${file?.name})", LOG_WAIT_MS)
            } catch (_: Throwable) {
                // The crash itself goes on below.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private const val STACK_LINE = 4
}
