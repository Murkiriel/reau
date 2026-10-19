package com.reau.app.util

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.data.RideDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * On the emulator: the export zips every file of the diag
 * folder, the share intent hands a link the picked app can read (REau's FileProvider), and the share sheet opens.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsExportOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun entries(uri: Uri): List<String> = context.contentResolver.openInputStream(uri)!!.use { input ->
        val zip = ZipInputStream(input)
        generateSequence { zip.nextEntry }.map { it.name }.toList()
    }

    @Test
    fun theZipHasEveryFileOfTheFolderAndTheLinkReadsBack() {
        RideDiagnostics.startProcess("test")
        RideDiagnostics.logAndWait("test", "export-test-${System.nanoTime()}")
        val diag = RideDiagnostics.logDir()!!
        val zip = DiagnosticsExport.write(context)
        assertNotNull("a zip", zip)

        val intent = DiagnosticsExport.shareIntent(context, zip!!)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue("the picked app may read it", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        val inFolder = diag.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()
        assertEquals("every file of the folder", inFolder, entries(uri).sorted())
    }

    @Test
    fun theRecordingsGoInTheirFoldersWhenAskedFor() {
        RideDiagnostics.startProcess("test")
        val video = File(context.filesDir, "video").apply { mkdirs() }
        val marker = File(video, "export-test.h264").apply { writeText("x") }
        try {
            val zip = DiagnosticsExport.write(context, withRecordings = true)!!
            @Suppress("DEPRECATION") val uri = DiagnosticsExport.shareIntent(context, zip).getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertTrue("the recording in its folder", "video/export-test.h264" in entries(uri))
        } finally {
            marker.delete()
        }
    }

    @Test
    fun theShareSheetOpens() {
        RideDiagnostics.startProcess("test")
        val zip = DiagnosticsExport.write(context)!!
        val chooser = Intent.createChooser(DiagnosticsExport.shareIntent(context, zip), "Compartilhar registros")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        try {
            val deadline = System.currentTimeMillis() + 10_000
            var top = ""
            while (System.currentTimeMillis() < deadline) {
                top = shell("dumpsys activity activities").lines().firstOrNull { "mResumedActivity" in it || "topResumedActivity" in it }.orEmpty()
                if ("Chooser" in top || "Resolver" in top) break
                Thread.sleep(200)
            }
            assertTrue("the share sheet on top: $top", "Chooser" in top || "Resolver" in top)
        } finally {
            shell("input keyevent KEYCODE_BACK")
        }
    }
}
