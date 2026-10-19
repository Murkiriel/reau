package com.reau.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Privacy: "Ver o registro" inside the app. The log files of the
 * diag folder, newest first, and the end of one of them (a ride file reaches 50 MB), from a whole line.
 */
class LogFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the log files, newest first, without the marks or other files`() {
        val d = tmp.newFolder("diag")
        File(d, "ride-20261001-080000.log").apply { writeText("a"); setLastModified(1_000) }
        File(d, "logcat-20261002-080000.txt").apply { writeText("b"); setLastModified(3_000) }
        File(d, "crash-20261001-090000.txt").apply { writeText("c"); setLastModified(2_000) }
        File(d, "crash-reported").writeText("1")
        File(d, "notes.txt").writeText("x")
        assertEquals(
            listOf("logcat-20261002-080000.txt", "crash-20261001-090000.txt", "ride-20261001-080000.log"),
            LogFiles.list(d).map { it.name },
        )
    }

    @Test
    fun `a small file is read whole`() {
        val f = tmp.newFile("ride-a.log").apply { writeText("one\ntwo\n") }
        val tail = LogFiles.tail(f, maxBytes = 1_000)
        assertEquals("one\ntwo\n", tail.text)
        assertEquals(0L, tail.skippedBytes)
    }

    @Test
    fun `a large file is read from its end, starting at a whole line, and says how much was left out`() {
        val lines = (1..1_000).joinToString("") { "line $it of the ride\n" }
        val f = tmp.newFile("ride-b.log").apply { writeText(lines) }
        val tail = LogFiles.tail(f, maxBytes = 500)
        assertTrue("from a whole line: ${tail.text.take(30)}", tail.text.startsWith("line "))
        assertTrue("to the end", tail.text.endsWith("line 1000 of the ride\n"))
        assertTrue("within the bytes asked", tail.text.length <= 500)
        assertEquals(f.length() - tail.text.toByteArray().size, tail.skippedBytes)
    }

    @Test
    fun `accents and other multi-byte characters stay whole`() {
        val f = tmp.newFile("ride-c.log").apply { writeText("projeção ligada\n".repeat(100)) }
        val tail = LogFiles.tail(f, maxBytes = 300)
        assertTrue(tail.text, tail.text.lines().filter { it.isNotEmpty() }.all { it == "projeção ligada" })
    }
}
