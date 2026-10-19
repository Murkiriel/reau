package com.reau.app.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The copy of REau's logcat no longer flushes the file for every line; it flushes once a second and when the file is
 * closed (a new file at its size).
 */
class OwnLogcatFlushTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.newFolder("diag")
        OwnLogcat.testOnlyUseDir(dir)
    }

    @After
    fun tearDown() = OwnLogcat.testOnlyReset()

    @Test
    fun `a line is not flushed on its own, but within the second`() {
        OwnLogcat.write("10-08 12:00:00.000  100  100 I REau: first line")
        assertFalse("not flushed per line", OwnLogcat.currentFile()!!.readText().contains("first line"))
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && !OwnLogcat.currentFile()!!.readText().contains("first line")) {
            Thread.sleep(50)
        }
        assertTrue("flushed within the second", OwnLogcat.currentFile()!!.readText().contains("first line"))
    }

    @Test
    fun `a file closed for its size has all its lines`() {
        OwnLogcat.maxFileBytes = 100
        val first = OwnLogcat.currentFile()!!
        repeat(3) { OwnLogcat.write("10-08 12:00:00.000  100  100 I REau: line $it of forty characters") }
        assertTrue("a new file", OwnLogcat.currentFile() != first)
        val text = first.readText()
        assertTrue("the closed file has its lines at once: $text", text.contains("line 0 of") && text.contains("line 1 of"))
    }
}
