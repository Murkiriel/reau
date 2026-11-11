package com.reau.app.data

import java.io.File
import java.io.RandomAccessFile

/**
 * "Ver o registro": what the logs hold, inside the app.
 * [list] gives the diag folder's log files ([DiagSpace.isLog]) newest first; [tail] the end of one, since a ride file
 * reaches 50 MB: at most [tail]'s maxBytes, from the start of a whole line (a newline byte is never inside a UTF-8
 * character, so the text decodes whole).
 */
object LogFiles {
    /** The end of a file: [text], and how many bytes before it were left out. */
    data class Tail(val text: String, val skippedBytes: Long)

    /** A log file as the list shows it: read once (one look at the disk), so that drawing the list touches no file. */
    data class Entry(val file: File, val name: String, val lastModifiedMs: Long, val lengthBytes: Long)

    private const val NEWLINE: Byte = 0x0A

    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && DiagSpace.isLog(f.name) }.orEmpty().sortedByDescending { it.lastModified() }

    /** [list] with each file's name, time and size read once, newest first; for the screen to ask off the main thread and draw without reading files. */
    fun entries(dir: File): List<Entry> =
        dir.listFiles { f -> f.isFile && DiagSpace.isLog(f.name) }.orEmpty()
            .map { Entry(it, it.name, it.lastModified(), it.length()) }.sortedByDescending { it.lastModifiedMs }

    fun tail(file: File, maxBytes: Int): Tail = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val start = (length - maxBytes).coerceAtLeast(0)
        val bytes = ByteArray((length - start).toInt())
        raf.seek(start)
        raf.readFully(bytes)
        val from = if (start == 0L) 0 else afterFirstNewline(bytes)
        Tail(String(bytes, from, bytes.size - from, Charsets.UTF_8), start + from)
    }

    /** Where the first whole line begins in a chunk cut at an arbitrary byte (0 when it has no newline). */
    private fun afterFirstNewline(bytes: ByteArray): Int = bytes.indexOf(NEWLINE) + 1
}
