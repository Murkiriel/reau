// The recordings go in the same zip when the rider wants them (zipDirs, write's withRecordings), and the share
// intent goes through REau's FileProvider.
package com.reau.app.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.reau.app.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Zips [com.reau.app.data.RideDiagnostics]' log directory so it can be shared straight
 * from Settings ("Compartilhar registros") instead of needing `adb pull` on a computer.
 */
object DiagnosticsExport {
    /** The recordings of Ajustes → Depuração, under the app's internal files (SessionRecorder, VideoLoop). */
    private val RECORDINGS = listOf("video", "sessions")

    /** Pure zipping, independent of Context so it can run in a plain JVM unit test. Every regular
     *  file directly inside [sourceDir] becomes one zip entry (flat — the diag dir has no
     *  subdirectories today); returns the number of files written. */
    internal fun zipDir(sourceDir: File, destZip: File): Int {
        var count = 0
        ZipOutputStream(FileOutputStream(destZip)).use { zos ->
            val files = sourceDir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
            for (f in files) {
                zos.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    /** The first entry of an exported zip: what the app was and which connection experiments were on when it was exported. */
    internal const val HEADER_ENTRY = MaskedZip.HEADER_ENTRY

    /**
     * As [zipDir], for several folders in one zip: each source's files go under its name ("" for the top). With a [header], it is the first entry.
     * The files of the top ("" : the text logs) go through [LogMask], the same masking as the collection script's (the dash's Wi-Fi name, the MACs
     * and the other networks' names), with [maskNames] (more names to hide wherever they stand) added to the ones the logs themselves say; the
     * recordings (`video`, `sessions`) are binary and go as they are. [mask] false only for a caller that wants the raw files.
     */
    internal fun zipDirs(
        sources: List<Pair<String, File>>, destZip: File, header: String? = null, mask: Boolean = true, maskNames: Collection<String> = emptySet(),
    ): Int = MaskedZip.write(sources, destZip, header, mask, maskNames)

    /** What [export] adds to the zip besides the files: the [header] (its first entry) and the [maskNames] to hide wherever they stand in the logs. */
    internal class ExportExtras(val header: String? = null, val maskNames: Collection<String> = emptySet())

    /** There is not the room the zip may take (the recordings alone can be hundreds of MB): nothing was written. */
    class NotEnoughSpace(val needed: Long, val free: Long) : IOException("not enough free space to export the logs ($free of $needed bytes)")

    /** Room to keep free besides the files' own size, for the zip's headers and for whatever else the phone is doing. */
    private const val SPACE_MARGIN = 16L * 1024 * 1024
    private const val ZIP_PREFIX = "diagnostics-logs"

    // The space that is free now, not what Android could clear for us: the cache of other apps is not ours to evict for a zip.
    @SuppressLint("UsableSpace")
    private fun freeOn(dir: File): Long = dir.usableSpace

    private fun filesOf(sources: List<Pair<String, File>>): List<File> =
        sources.flatMap { (_, dir) -> dir.listFiles()?.filter { it.isFile }.orEmpty() }

    /**
     * The zip of [sources] in [dir], under a name of its own (`diagnostics-logs-<date>-<time>.zip`): written to a
     * `.part` file first and renamed when whole, and the earlier zips are deleted only then, so a full disk or a crash
     * halfway leaves the last good one as it was. Checks [freeBytes] of [dir] against the files' size (plus a margin)
     * before writing a byte, and throws [NotEnoughSpace] when short. Null when there is nothing to export.
     */
    internal fun export(
        sources: List<Pair<String, File>>,
        dir: File,
        freeBytes: (File) -> Long = ::freeOn,
        nowMs: Long = System.currentTimeMillis(),
        extras: ExportExtras = ExportExtras(),
    ): File? {
        val header = extras.header
        val files = filesOf(sources)
        if (files.isEmpty()) return null
        val needed = files.sumOf { it.length() } + (header?.length ?: 0) + SPACE_MARGIN
        val free = freeBytes(dir)
        if (free < needed) throw NotEnoughSpace(needed, free)

        val part = File(dir, "$ZIP_PREFIX.part")
        var count = 0
        try {
            count = zipDirs(sources, part, header, maskNames = extras.maskNames)
        } finally {
            if (count == 0) part.delete()           // failed halfway, or nothing went in
        }
        return if (count == 0) null else publish(part, dir, nowMs)
    }

    /** [part] renamed to the next free name of [dir], and only then the earlier zips deleted. */
    private fun publish(part: File, dir: File, nowMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(nowMs))
        var zip = File(dir, "$ZIP_PREFIX-$stamp.zip")
        var n = 1
        while (zip.exists()) zip = File(dir, "$ZIP_PREFIX-$stamp-${++n}.zip")
        if (!part.renameTo(zip)) {
            part.delete()
            throw IOException("could not name the exported zip")
        }
        dir.listFiles { f -> f != zip && f.name.startsWith(ZIP_PREFIX) && f.name.endsWith(".zip") }?.forEach { it.delete() }
        return zip
    }

    /** The zips left in [dir] by an earlier share, and any half-written one, deleted: they hold logs and would sit in the cache until the next share. */
    internal fun forgetZips(dir: File): Int =
        dir.listFiles { f -> f.name.startsWith(ZIP_PREFIX) && (f.name.endsWith(".zip") || f.name.endsWith(".part")) }
            ?.count { it.delete() } ?: 0

    /** The folder of the cache the zips are written to and shared from (`<cacheDir>/shared`); a new projection forgets what an earlier share left in it. */
    const val SHARED_DIR = "shared"

    /** Writes `<cacheDir>/shared/diagnostics-logs-<stamp>.zip` from the current diag log dir (and the recordings, with
     *  [withRecordings]) and returns it, or null when there's nothing to export yet. Throws [NotEnoughSpace] when short. */
    fun write(context: Context, withRecordings: Boolean = false): File? {
        val sourceDir = com.reau.app.data.RideDiagnostics.logDir() ?: return null
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val external: File? = context.filesDir // privacy: internal storage (AppFolders)
        val sources = if (withRecordings && external != null) {
            listOf("" to sourceDir) + RECORDINGS.map { it to File(external, it) }
        } else {
            listOf("" to sourceDir)
        }
        val app = "app ${com.reau.app.BuildConfig.VERSION_NAME} (${com.reau.app.BuildConfig.VERSION_CODE}, ${com.reau.app.BuildConfig.GIT_COMMIT})"
        val read = runCatching {
            com.reau.app.settings.SettingsInUse.experimentsLine(com.reau.app.settings.SettingsStore.open(context).load())
        }
        // The paired dash's own Wi-Fi name is hidden wherever it stands, even if it does not start the way a dash's does.
        val dashName = runCatching { com.reau.app.dash.DashPairing.open(context).ssid }.getOrNull().orEmpty()
        val extras = ExportExtras(reportHeader(app, read.getOrNull(), readable = read.isSuccess), listOf(dashName))
        return export(sources, dir, extras = extras)
    }

    /** Ajustes' button: the zip written off the main thread, then the share sheet (or a word that there is nothing yet). */
    fun shareFrom(activity: Activity, withRecordings: Boolean) {
        com.reau.app.data.RideDiagnostics.log("ui", "the rider shared the logs" + if (withRecordings) " with the recordings" else "")
        thread(name = "share-logs") {
            val result = runCatching { write(activity, withRecordings) }
            val zip = result.getOrNull()
            activity.runOnUiThread {
                if (result.exceptionOrNull() is NotEnoughSpace) {
                    Toast.makeText(activity, R.string.share_logs_no_space, Toast.LENGTH_LONG).show()
                } else if (zip == null) {
                    Toast.makeText(activity, R.string.share_logs_none, Toast.LENGTH_SHORT).show()
                } else {
                    activity.startActivity(Intent.createChooser(shareIntent(activity, zip), activity.getString(R.string.share_logs_title)))
                }
            }
        }
    }

    /** The Android share sheet's intent for [zip], readable by the app the rider picks. */
    fun shareIntent(context: Context, zip: File): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * The text of [DiagnosticsExport.HEADER_ENTRY]: the app, then the line of the connection experiments that are on ([experimentsLine], the one the logs
 * carry as `connection experiments ON: …`) or the word that none is. Written at the top so that whoever opens the zip reads it before
 * any log; each ride's own line is in its log, this is what is set at the moment of the export.
 */
internal fun reportHeader(appLine: String, experimentsLine: String?, readable: Boolean = true): String =
    "REau diagnostics report\n$appLine\n" +
        (if (!readable) "connection experiments: unknown (the settings could not be read)" else experimentsLine ?: "connection experiments: none on") +
        "\n(set at the moment of the export; each ride's own are the `settings` lines inside the logs)\n"
