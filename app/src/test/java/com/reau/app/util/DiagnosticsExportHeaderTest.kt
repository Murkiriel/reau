package com.reau.app.util

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The line of the connection experiments that are on, at the top of the exported report (item 35 of the 11/11/2026 list). */
class DiagnosticsExportHeaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = "app 0.3.0-dev (1, abc1234)"

    @Test
    fun `the header names the app and carries the experiments line as the logs write it`() {
        val line = "connection experiments ON: video pacing (RTP packets spaced out); start guard (experiments go off after a death right after a start)"
        assertEquals(
            "REau diagnostics report\n$app\n$line\n(set at the moment of the export; each ride's own are the `settings` lines inside the logs)\n",
            reportHeader(app, line),
        )
    }

    @Test
    fun `with none on the header says none`() {
        assertTrue(reportHeader(app, null), "\nconnection experiments: none on\n" in reportHeader(app, null))
    }

    @Test
    fun `settings that could not be read are not reported as none`() {
        val text = reportHeader(app, null, readable = false)
        assertTrue(text, "unknown" in text && "none on" !in text)
    }

    @Test
    fun `the exported zip has the header as its first entry, then the logs, and the logs are what they were`() {
        val diag = tmp.newFolder("diag")
        File(diag, "ride-1.log").writeText("one")
        File(diag, "ride-2.log").writeText("two")
        val out = tmp.newFolder("out")
        val header = reportHeader(app, "connection experiments ON: x")
        val zip = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { Long.MAX_VALUE }, header = header)!!
        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf(DiagnosticsExport.HEADER_ENTRY, "ride-1.log", "ride-2.log"), names)
            assertTrue(zf.getInputStream(zf.getEntry(DiagnosticsExport.HEADER_ENTRY)).readBytes().decodeToString().contains("connection experiments ON: x"))
            assertEquals("one", zf.getInputStream(zf.getEntry("ride-1.log")).readBytes().decodeToString())
        }
    }

    @Test
    fun `without a header the zip is as it was`() {
        val diag = tmp.newFolder("diag2")
        File(diag, "ride-1.log").writeText("one")
        val out = tmp.newFolder("out2")
        val zip = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { Long.MAX_VALUE })!!
        ZipFile(zip).use { zf -> assertEquals(listOf("ride-1.log"), zf.entries().asSequence().map { it.name }.toList()) }
    }

    @Test
    fun `a header alone is not worth a zip, with no log there is nothing to export`() {
        val diag = tmp.newFolder("diag3")
        assertNull(DiagnosticsExport.export(listOf("" to diag), tmp.newFolder("out3"), freeBytes = { Long.MAX_VALUE }, header = "x"))
    }
}
