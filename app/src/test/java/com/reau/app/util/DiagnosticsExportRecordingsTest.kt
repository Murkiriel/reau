package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * If the rider wants, the debug recordings (`video/`, `sessions/`) go in the same zip, each in a folder of its name,
 * next to the logs.
 */
class DiagnosticsExportRecordingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(zip: File) = ZipFile(zip).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

    @Test
    fun `the logs at the top and each recording folder under its own name`() {
        val diag = tmp.newFolder("diag").apply { File(this, "ride-1.log").writeText("a"); File(this, "logcat-1.txt").writeText("b") }
        val video = tmp.newFolder("video").apply { File(this, "stream.h264").writeText("c") }
        val sessions = tmp.newFolder("sessions").apply { File(this, "s1.aap").writeText("d") }
        val dest = File(tmp.root, "out.zip")

        val count = DiagnosticsExport.zipDirs(listOf("" to diag, "video" to video, "sessions" to sessions), dest)

        assertEquals(4, count)
        assertEquals(listOf("logcat-1.txt", "ride-1.log", "video/stream.h264", "sessions/s1.aap"), names(dest))
    }

    @Test
    fun `a recording folder that does not exist is left out`() {
        val diag = tmp.newFolder("diag").apply { File(this, "ride-1.log").writeText("a") }
        val dest = File(tmp.root, "out.zip")
        assertEquals(1, DiagnosticsExport.zipDirs(listOf("" to diag, "video" to File(tmp.root, "none")), dest))
        assertEquals(listOf("ride-1.log"), names(dest))
    }
}
