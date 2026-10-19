package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DiagnosticsExportTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `zips every file directly in the source dir`() {
        val src = tmp.newFolder("diag")
        File(src, "ride-1.log").writeText("hello")
        File(src, "ride-2.log").writeText("world")
        val dest = File(tmp.root, "out.zip")

        val count = DiagnosticsExport.zipDir(src, dest)

        assertEquals(2, count)
        ZipFile(dest).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("ride-1.log", "ride-2.log"), names)
        }
    }

    @Test
    fun `an empty source dir produces a zip with zero entries, not a crash`() {
        val src = tmp.newFolder("empty-diag")
        val dest = File(tmp.root, "out2.zip")

        val count = DiagnosticsExport.zipDir(src, dest)

        assertEquals(0, count)
        ZipFile(dest).use { zf -> assertTrue(zf.entries().asSequence().toList().isEmpty()) }
    }

    @Test
    fun `subdirectories inside the source dir are skipped, not walked`() {
        val src = tmp.newFolder("diag-with-subdir")
        File(src, "ride-1.log").writeText("hello")
        File(src, "nested").mkdirs()
        val dest = File(tmp.root, "out3.zip")

        val count = DiagnosticsExport.zipDir(src, dest)

        assertEquals(1, count)
    }
}
