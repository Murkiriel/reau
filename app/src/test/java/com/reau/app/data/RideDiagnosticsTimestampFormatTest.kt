package com.reau.app.data

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the actual shape of what log() writes to disk — "+NNNNms" relative to session start,
 * plus the absolute HH:mm:ss.SSS wall clock — which no existing test asserted (RideDiagnosticsRotateTest
 * covers file rotation, RideDiagnosticsAsyncWriteTest covers write timing/threading, neither parses
 * a line's own content).
 */
class RideDiagnosticsTimestampFormatTest {
    private lateinit var logFile: File

    @Before
    fun setUp() {
        logFile = Files.createTempFile("ride-diag-format-test", ".log").toFile()
        RideDiagnostics.testOnlySetFile(logFile)
    }

    @After
    fun tearDown() {
        logFile.delete()
    }

    private fun awaitLines(expected: Int): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && logFile.readLines().size < expected) {
            Thread.sleep(20)
        }
        return logFile.readLines()
    }

    @Test
    fun `a logged line matches the absolute-clock plus relative-offset format`() {
        RideDiagnostics.log("test", "hello world")
        val lines = awaitLines(1)
        assertTrue("expected 1 line, got ${lines.size}: $lines", lines.size == 1)
        val lineRegex = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}  \+\s*\d+ms  \[test] hello world$""")
        assertTrue("line didn't match expected format: '${lines[0]}'", lineRegex.matches(lines[0]))
    }

    @Test
    fun `the relative offset grows across two calls a moment apart`() {
        RideDiagnostics.log("test", "first")
        Thread.sleep(30)
        RideDiagnostics.log("test", "second")
        val lines = awaitLines(2)
        assertTrue("expected 2 lines, got ${lines.size}: $lines", lines.size == 2)

        val offsetRegex = Regex("""\+\s*(\d+)ms""")
        val firstOffset = offsetRegex.find(lines[0])!!.groupValues[1].toLong()
        val secondOffset = offsetRegex.find(lines[1])!!.groupValues[1].toLong()
        assertTrue(
            "second offset ($secondOffset) should be greater than first ($firstOffset) — it's relative to session start, not fixed",
            secondOffset > firstOffset,
        )
    }
}
