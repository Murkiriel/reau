package com.reau.app.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rider's control over the logs, as REau's privacy text promises: the log can be switched off from the screen, and
 * everything it wrote can be deleted, recordings too if asked. Only the two marks of what was already reported stay
 * (they hold no ride, only how far crashes and exits were told; without them the old ones would be told again).
 */
class LogsControlTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var diag: File

    @Before
    fun setUp() {
        diag = tmp.newFolder("diag")
        RideDiagnostics.testOnlyUseDir(diag, today = { "20261009" })
        OwnLogcat.testOnlyUseDir(diag)
    }

    @After
    fun tearDown() {
        OwnLogcat.testOnlyReset()
        RideDiagnostics.testOnlyReset()
    }

    private fun rides() = diag.listFiles { f -> f.name.startsWith("ride-") }!!.toList()

    @Test
    fun `clearing deletes every file of the folders given but the reported marks, and says how much`() {
        val video = tmp.newFolder("video")
        File(diag, "crash-20261008-101010.txt").writeText("trace")
        File(diag, "crash-reported").writeText("1")
        File(diag, "exit-reported").writeText("2")
        File(video, "video-1.h264").writeBytes(ByteArray(2048))
        File(video, "video-1.csv").writeText("t")
        val cleared = LogsCleanup.clear(listOf(diag, video, File(tmp.root, "missing")))
        assertTrue("the marks stay", File(diag, "crash-reported").exists() && File(diag, "exit-reported").exists())
        assertEquals(listOf("crash-reported", "exit-reported"), diag.list()!!.filter { !it.startsWith("logcat-") }.sorted())
        assertEquals("the recordings went", 0, video.list()!!.size)
        assertTrue("counted: $cleared", cleared.files >= 3 && cleared.bytes >= 2048)
    }

    @Test
    fun `the open log is closed, the folder cleared, and a new file goes on saying it`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.logAndWait("ui", "before the deletion")
        val old = rides().single()
        val said = RideDiagnostics.clearAndReopen { d -> LogsCleanup.clear(listOf(d)).said() }
        assertTrue("it said what went: $said", said != null)
        // The new file may take the old one's name (same second): what tells them apart is what is inside.
        assertTrue("the old file went or is the new one", !old.exists() || !old.readText().contains("before the deletion"))
        RideDiagnostics.logAndWait("ui", "after the deletion")
        val text = rides().single().readText()
        assertTrue(text, text.contains("the rider deleted the logs"))
        assertTrue(text, text.contains("[ui] after the deletion"))
        assertFalse("nothing of before", text.contains("before the deletion"))
    }

    @Test
    fun `switched off, nothing is written and the file is closed, switched on, a new file starts`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.switchOff("the rider")
        assertFalse(RideDiagnostics.enabled)
        RideDiagnostics.log("ui", "while off")
        assertFalse("closed", RideDiagnostics.testOnlyWriterIsOpen())
        val first = rides().single()
        assertTrue(first.readText(), first.readText().contains("log switched off: the rider"))
        assertFalse(first.readText().contains("while off"))

        RideDiagnostics.switchOn("the rider")
        assertTrue(RideDiagnostics.enabled)
        RideDiagnostics.logAndWait("ui", "back on")
        val second = rides().single { it != first }
        assertTrue(second.readText(), second.readText().contains("process start: the rider"))
        assertTrue(second.readText().contains("[ui] back on"))
    }

    @Test
    fun `the days chosen apply at once and the log says what went`() {
        RideDiagnostics.startProcess("app")
        val old = File(diag, "ride-20260101-080000.log").apply { writeText("an old ride"); setLastModified(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000) }
        RideDiagnostics.applyKeepDays(30)
        assertFalse("older than 30 days went", old.exists())
        RideDiagnostics.logAndWait("ui", "after")
        val text = rides().single().readText()
        assertTrue(text, text.contains("diag age: 1 file older than 30 days removed"))
    }

    @Test
    fun `the logcat copy stops writing while paused, and starts a new file when resumed`() {
        OwnLogcat.pause()
        assertNull("no file while paused", OwnLogcat.currentFile())
        OwnLogcat.maxFileBytes = 1 // a line past the size must not open a new file while paused
        OwnLogcat.write("10-09 12:00:00.000  100  100 I REau: while paused")
        OwnLogcat.write("10-09 12:00:00.001  100  100 I REau: while paused")
        assertNull("still no file", OwnLogcat.currentFile())
        assertTrue("nothing written", diag.listFiles { f -> f.name.startsWith("logcat-") }!!.all { !it.readText().contains("while paused") })
        OwnLogcat.resume()
        OwnLogcat.write("10-09 12:00:01.000  100  100 I REau: resumed")
        val deadline = System.currentTimeMillis() + 3_000 // flushed once a second
        while (System.currentTimeMillis() < deadline && !OwnLogcat.currentFile()!!.readText().contains("resumed")) Thread.sleep(50)
        assertTrue(OwnLogcat.currentFile()!!.readText().contains("resumed"))
    }
}
