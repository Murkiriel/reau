package com.reau.app.data

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What made the app crash, written before the process dies: one
 * `crash-AAAAMMDD-HHMMSS-mmm.txt` per crash in [dir] (the ride log's folder), with the time, the thread, the time since
 * the process started and the whole stack, causes included. Written synchronously: the process is about to go.
 *
 * The next run asks [unreported] for the newest crash not yet told and tells it once ([markReported]).
 */
class CrashRecorder(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    /** Writes the crash; null if the folder cannot be written (never throws: this runs inside a dying process). */
    fun record(thread: Thread, error: Throwable, uptimeMs: Long): File? = try {
        dir.mkdirs()
        val at = now()
        var file = File(dir, "crash-${NAME.format(Date(at))}.txt")
        var n = 1
        while (file.exists()) file = File(dir, "crash-${NAME.format(Date(at))}-${n++}.txt")
        file.writeText(
            buildString {
                appendLine("REau crash")
                appendLine("time: ${STAMP.format(Date(at))}")
                appendLine("thread: ${thread.name} (id ${thread.id})")
                appendLine("uptime: $uptimeMs ms")
                append(error.stackTraceToString())
            },
        )
        file
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** The newest crash file the app has not told yet, or null. */
    fun unreported(): File? {
        val newest = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.name } ?: return null
        val told = runCatching { File(dir, REPORTED).readText().trim() }.getOrDefault("")
        return newest.takeIf { it.name > told }
    }

    fun markReported(file: File) {
        runCatching { File(dir, REPORTED).writeText(file.name) }
    }

    companion object {
        private const val REPORTED = "crash-reported"
        private val NAME get() = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        private val STAMP get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        /** One line for the ride log: the exception and where it was thrown. */
        fun summary(error: Throwable): String = "${error.javaClass.name}: ${error.message} at ${error.stackTrace.firstOrNull()}"
    }
}
