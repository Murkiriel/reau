package com.reau.app.data

import java.io.File

/**
 * Logs and debug recordings older than the rider's choice of days ([com.reau.app.settings.REauSettings.logKeepDays]) go
 * by themselves, whenever a log file is opened and when the app starts. Only the log files of the diag folder
 * ([DiagSpace.isLog]) and the files of the recording folders; never one that is open, never the reported marks; zero
 * days is never.
 */
object LogAge {
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000
    private val RECORDINGS = setOf("video", "sessions")

    /** Removes what is older than [keepDays] in [dirs]; what it removed, said, or null if nothing. */
    fun trim(dirs: List<File>, keepDays: Int, nowMs: Long, open: Set<String>): String? {
        if (keepDays <= 0) return null
        val limit = nowMs - keepDays * MS_PER_DAY
        var removed = 0
        for (dir in dirs) {
            val recordings = dir.name in RECORDINGS
            for (f in dir.listFiles().orEmpty()) {
                if (goes(f, recordings, open, limit) && runCatching { f.delete() }.getOrDefault(false)) removed++
            }
        }
        val word = if (removed == 1) "file" else "files"
        return if (removed == 0) null else "$removed $word older than $keepDays days removed"
    }

    /** One of ours (a log of the diag folder, or any recording), closed, and last written before [limit]. */
    private fun goes(f: File, recordings: Boolean, open: Set<String>, limit: Long): Boolean {
        val ours = f.isFile && (recordings || DiagSpace.isLog(f.name))
        return ours && f.name !in open && f.lastModified() < limit
    }
}
