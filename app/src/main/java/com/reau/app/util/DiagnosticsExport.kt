// The debug recordings go in the same zip when the rider wants them (zipDirs, write's withRecordings), and the share
// intent goes through REau's FileProvider.
package com.reau.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.reau.app.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Zips [com.reau.app.data.RideDiagnostics]' log directory so it can be shared straight
 * from Settings ("Compartilhar registros") instead of needing `adb pull` on a computer.
 */
object DiagnosticsExport {
    /** The recordings of Ajustes → Depuração, under the app's external files (SessionRecorder, VideoLoop). */
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

    /** As [zipDir], for several folders in one zip: each source's files go under its name ("" for the top). */
    internal fun zipDirs(sources: List<Pair<String, File>>, destZip: File): Int {
        val entries = sources.flatMap { (name, dir) ->
            val prefix = if (name.isEmpty()) "" else "$name/"
            dir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }.map { prefix + it.name to it }
        }
        ZipOutputStream(FileOutputStream(destZip)).use { zos ->
            for ((entry, f) in entries) {
                zos.putNextEntry(ZipEntry(entry))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return entries.size
    }

    /** Writes `<cacheDir>/shared/diagnostics-logs.zip` from the current diag log dir (and the recordings, with
     *  [withRecordings]) and returns it, or null when there's nothing to export yet. */
    fun write(context: Context, withRecordings: Boolean = false): File? {
        val sourceDir = com.reau.app.data.RideDiagnostics.logDir() ?: return null
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "diagnostics-logs.zip")
        val external: File? = context.filesDir // privacy: internal storage (AppFolders)
        val count = if (withRecordings && external != null) {
            zipDirs(listOf("" to sourceDir) + RECORDINGS.map { it to File(external, it) }, zip)
        } else {
            zipDir(sourceDir, zip)
        }
        return if (count > 0) zip else null
    }

    /** Ajustes' button: the zip written off the main thread, then the share sheet (or a word that there is nothing yet). */
    fun shareFrom(activity: Activity, withRecordings: Boolean) {
        com.reau.app.data.RideDiagnostics.log("ui", "the rider shared the logs" + if (withRecordings) " with the recordings" else "")
        thread(name = "share-logs") {
            val zip = runCatching { write(activity, withRecordings) }.getOrNull()
            activity.runOnUiThread {
                if (zip == null) {
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
