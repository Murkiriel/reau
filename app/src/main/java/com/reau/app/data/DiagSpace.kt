package com.reau.app.data

import java.io.File

/**
 * The diag folder holds at most [CAP_BYTES]. [trim] removes the oldest logs of every
 * kind (ride, logcat, crash, ANR, native crash) until the folder fits, never one that is open, and never the marks of
 * what was already reported (`crash-reported`, `exit-reported`) nor files of other kinds. Called whenever a new ride
 * or logcat file is opened.
 */
object DiagSpace {
    const val CAP_BYTES = 200L * 1024 * 1024
    private const val KB = 1024L
    private val LOG = Regex("""^(ride|logcat|crash|anr|native-crash)-.+\.(log|txt)$""")

    fun isLog(name: String): Boolean = LOG.matches(name)

    /** Fits [dir] under [capBytes], leaving the files named in [open]; what was removed, or null if nothing was. */
    fun trim(dir: File, capBytes: Long, open: Set<String>): String? {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        var removed = 0
        var removedBytes = 0L
        val candidates = if (total <= capBytes) emptyList() else files.filter { isLog(it.name) && it.name !in open }
        for (f in candidates.sortedBy { it.lastModified() }) {
            if (total <= capBytes) break
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= size
                removed++
                removedBytes += size
            }
        }
        val word = if (removed == 1) "file" else "files"
        return if (removed == 0) null else "$removed $word removed (${removedBytes / KB} KB), ${total / KB} KB left of ${capBytes / KB} KB"
    }
}
