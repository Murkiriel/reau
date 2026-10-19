package com.reau.app.data

import android.content.Context
import java.io.File

/**
 * The rider's "Apagar registros": everything the logs wrote goes (the diag folder,
 * the zip last shared and, if asked, the debug recordings), but the two marks of what was already reported. They hold
 * no ride, only how far the crashes and the exits were told; without them, the next start would tell the old ones
 * again into the new log.
 */
object LogsCleanup {
    private val KEEP = setOf("crash-reported", "exit-reported")
    private const val KB = 1024L

    /** What went: [files] files, [bytes] bytes. */
    data class Cleared(val files: Int, val bytes: Long) {
        fun said() = "$files ${if (files == 1) "file" else "files"} (${bytes / KB} KB)"

        operator fun plus(other: Cleared) = Cleared(files + other.files, bytes + other.bytes)
    }

    /** Deletes every file directly inside [dirs] but the reported marks; a folder that does not exist is nothing. */
    fun clear(dirs: List<File>): Cleared {
        var files = 0
        var bytes = 0L
        for (f in dirs.flatMap { it.listFiles()?.toList().orEmpty() }) {
            if (!f.isFile || f.name in KEEP) continue
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                files++
                bytes += size
            }
        }
        return Cleared(files, bytes)
    }

    /**
     * The button: the logcat copy paused, the ride log closed, the folders cleared, and both going on in new files if
     * the log is on (the new ride log starts by saying what went). Off the main thread: it deletes files.
     */
    fun run(context: Context, withRecordings: Boolean): Cleared {
        val extra = listOf(File(context.cacheDir, "shared")) +
            if (withRecordings) RECORDINGS.map { AppFolders.recordings(context, it) } else emptyList()
        var cleared = Cleared(0, 0)
        OwnLogcat.pause()
        try {
            val said = RideDiagnostics.clearAndReopen { diag ->
                cleared = clear(listOf(diag) + extra)
                cleared.said() + if (withRecordings) ", recordings included" else ""
            }
            if (said == null) cleared = clear(extra)
        } finally {
            if (RideDiagnostics.enabled) OwnLogcat.resume()
        }
        return cleared
    }

    /** The recordings of Ajustes → Depuração, under the app's external files (as DiagnosticsExport shares them). */
    private val RECORDINGS = listOf("video", "sessions")
}
