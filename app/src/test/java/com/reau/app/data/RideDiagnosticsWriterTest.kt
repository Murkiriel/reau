package com.reau.app.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The writer keeps the file open instead of opening and closing it for every line, the queue of lines waiting for it
 * has a ceiling (and the next line written says how many went), and the kill switch is read from memory, not from the
 * preferences, on every line.
 */
class RideDiagnosticsWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private var day = "20261008"

    @Before
    fun setUp() {
        dir = tmp.newFolder("diag")
        RideDiagnostics.testOnlyUseDir(dir, today = { day })
    }

    @After
    fun tearDown() = RideDiagnostics.testOnlyReset()

    private fun files() = dir.listFiles { f -> f.name.startsWith("ride-") }!!.sortedBy { it.name }

    @Test
    fun `one file is opened once for all its lines`() {
        RideDiagnostics.startProcess("app")
        repeat(100) { RideDiagnostics.log("ui", "line $it") }
        RideDiagnostics.logAndWait("ui", "last", timeoutMs = 20_000)
        assertTrue(files().single().readText().contains("[ui] last"))
        assertEquals("opened once", 1, RideDiagnostics.testOnlyWriterOpens())
    }

    @Test
    fun `a new file is opened once too, and the lines go on in it`() {
        RideDiagnostics.maxFileBytes = 300
        RideDiagnostics.startProcess("app")
        repeat(20) { RideDiagnostics.log("ui", "line $it with some text to fill the file up") }
        RideDiagnostics.logAndWait("ui", "last", timeoutMs = 20_000)
        val all = files()
        assertTrue("more than one file: ${all.map { it.name }}", all.size > 1)
        assertEquals("one opening per file", all.size, RideDiagnostics.testOnlyWriterOpens())
        val text = all.joinToString("") { it.readText() }
        assertTrue("every line in some file", (0 until 20).all { text.contains("[ui] line $it with") })
    }

    @Test
    fun `the end of a projection closes its file`() {
        RideDiagnostics.start("projection")
        RideDiagnostics.logAndWait("projection", "during")
        assertTrue("open while the projection lasts", RideDiagnostics.testOnlyWriterIsOpen())
        RideDiagnostics.stop("projection")
        assertFalse("closed at its end", RideDiagnostics.testOnlyWriterIsOpen())
        assertTrue(files().single().readText().contains("session end: projection"))
    }

    @Test
    fun `the end of the process file closes it`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.logAndWait("ui", "during")
        assertTrue(RideDiagnostics.testOnlyWriterIsOpen())
        RideDiagnostics.stopProcess("test")
        assertFalse(RideDiagnostics.testOnlyWriterIsOpen())
        assertTrue(files().single().readText().contains("process end: test"))
    }

    @Test
    fun `past the queue's ceiling lines are dropped and the next line written says how many`() {
        val file = tmp.newFile("ride-queue.log")
        RideDiagnostics.testOnlySetFile(file)
        val release = RideDiagnostics.testOnlyHoldWriter()
        repeat(RideDiagnostics.MAX_QUEUED + 10) { RideDiagnostics.log("ui", "line $it.") }
        release()
        // Asked on the writer's thread, so answered once the queue has gone to disk: the next line has room again.
        assertTrue(RideDiagnostics.testOnlyWriterIsOpen())
        RideDiagnostics.logAndWait("ui", "after", timeoutMs = 30_000)
        RideDiagnostics.logAndWait("ui", "and after", timeoutMs = 30_000) // the count is said once, not on every line
        val lines = file.readLines()
        assertTrue("the queue's lines written", lines.any { it.endsWith("line ${RideDiagnostics.MAX_QUEUED - 1}.") })
        assertFalse("the ones past it dropped", lines.any { it.endsWith("line ${RideDiagnostics.MAX_QUEUED}.") })
        val after = lines.indexOfFirst { it.endsWith("[ui] after") }
        assertTrue("the next line written: ${lines.takeLast(3)}", after > 0)
        assertTrue("said before it: ${lines[after - 1]}", lines[after - 1].endsWith("log: 10 lines dropped, the writer fell behind"))
        assertEquals("said once", 1, lines.count { it.contains("lines dropped") })
    }

    /** Android Auto's "Config response" came in four lines and left 93 lines with no time in a ride's log. */
    @Test
    fun `a message of several lines is one line of the log, with the time in front`() {
        val file = tmp.newFile("ride-multi.log")
        RideDiagnostics.testOnlySetFile(file)
        RideDiagnostics.log("aa", "[AA] Config response: status: HEADUNIT\nmax_unacked: 12\nconfiguration_indices: 0\n (maxUnacked=12)")
        RideDiagnostics.logAndWait("ui", "next")
        val lines = file.readLines()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].matches(Regex("""\d\d:\d\d:\d\d\.\d{3}  \+ *\d+ms  \[aa] .*""")))
        assertTrue(lines[0], lines[0].endsWith("status: HEADUNIT ⏎ max_unacked: 12 ⏎ configuration_indices: 0 ⏎ (maxUnacked=12)"))
    }

    @Test
    fun `carriage returns and a final line break leave no empty piece nor a dangling separator`() {
        val file = tmp.newFile("ride-crlf.log")
        RideDiagnostics.testOnlySetFile(file)
        RideDiagnostics.logAndWait("ui", "a\r\nb\n\nc\n")
        val lines = file.readLines()
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines[0], lines[0].endsWith("[ui] a ⏎ b ⏎ c"))
    }

    /** A log with no level could not be filtered for what went wrong. */
    @Test
    fun `a warning and an error carry their level at the start of the text, the tag stays`() {
        val file = tmp.newFile("ride-level.log")
        RideDiagnostics.testOnlySetFile(file)
        RideDiagnostics.warn("dash", "TX send failing")
        RideDiagnostics.error("error", "session fail: gone")
        RideDiagnostics.logAndWait("ui", "plain")
        val lines = file.readLines()
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines[0], lines[0].endsWith("[dash] W: TX send failing"))
        assertTrue(lines[1], lines[1].endsWith("[error] E: session fail: gone"))
        assertTrue(lines[2], lines[2].endsWith("[ui] plain"))
    }

    @Test
    fun `a message of one piece is written as it is`() {
        val file = tmp.newFile("ride-one.log")
        RideDiagnostics.testOnlySetFile(file)
        RideDiagnostics.logAndWait("ui", "plain — text with | pipes and (parentheses)")
        assertTrue(file.readLines().single().endsWith("[ui] plain — text with | pipes and (parentheses)"))
    }

    @Test
    fun `the kill switch holds without preferences, read from memory`() {
        val file = tmp.newFile("ride-switch.log")
        RideDiagnostics.testOnlySetFile(file)
        RideDiagnostics.enabled = false
        RideDiagnostics.log("ui", "while off")
        RideDiagnostics.enabled = true
        RideDiagnostics.logAndWait("ui", "back on")
        val text = file.readText()
        assertFalse("off means off: $text", text.contains("while off"))
        assertTrue(text.contains("[ui] back on"))
    }
}
