package com.reau.app.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The zip of [DiagnosticsExport] (what "Compartilhar" shares): the files of several folders, the text logs of the top folder going through
 * [LogMask] and the recordings (binary) going as they are.
 */
internal object MaskedZip {
    /** The first entry of an exported zip: what the app was and which connection experiments were on when it was exported. */
    const val HEADER_ENTRY = "report-header.txt"

    /** A file of the zip: its entry name, and whether it is a text log that goes through [LogMask] (only the top folder's files, when masking is on). */
    private class Source(val name: String, val file: File, val masked: Boolean)

    /**
     * Each source's files under its name ("" for the top), with a [header] as the first entry. The names the logs say after a keyword are
     * hidden in every log of the zip, not only in the file that says them, with [maskNames] (more names to hide wherever they stand) added.
     * [mask] false only for a caller that wants the raw files. Returns the number of entries written.
     */
    fun write(sources: List<Pair<String, File>>, destZip: File, header: String?, mask: Boolean, maskNames: Collection<String>): Int {
        val entries = entriesOf(sources, mask)
        val names = namesToHide(entries, maskNames)
        ZipOutputStream(FileOutputStream(destZip)).use { zos ->
            if (header != null) put(zos, HEADER_ENTRY) { it.write(header.toByteArray(Charsets.UTF_8)) }
            for (entry in entries) put(zos, entry.name) { copy(entry, it, names) }
        }
        return entries.size + if (header != null) 1 else 0
    }

    private fun copy(entry: Source, out: ZipOutputStream, names: Set<String>) {
        if (entry.masked) LogMask.write(entry.file, out, names) else FileInputStream(entry.file).use { it.copyTo(out) }
    }

    private fun put(zos: ZipOutputStream, name: String, body: (ZipOutputStream) -> Unit) {
        zos.putNextEntry(ZipEntry(name))
        body(zos)
        zos.closeEntry()
    }

    private fun entriesOf(sources: List<Pair<String, File>>, mask: Boolean): List<Source> = sources.flatMap { (name, dir) ->
        val prefix = if (name.isEmpty()) "" else "$name/"
        dir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }.map { Source(prefix + it.name, it, mask && name.isEmpty()) }
    }

    private fun namesToHide(entries: List<Source>, extra: Collection<String>): Set<String> {
        val logs = entries.filter { it.masked }
        if (logs.isEmpty()) return emptySet()
        return logs.flatMapTo(mutableSetOf<String>()) { LogMask.networkNames(it.file) } + extra
    }
}
