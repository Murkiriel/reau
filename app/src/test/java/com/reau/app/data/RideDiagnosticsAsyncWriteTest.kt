package com.reau.app.data

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression coverage for moving RideDiagnostics' disk writes off the caller's thread
 * (`log()`/`raw()` used to do synchronous `File.appendText` while holding a lock, called
 * from the Main thread, the streaming loop's Dispatchers.Default, and even a MediaCodec callback
 * thread).
 */
class RideDiagnosticsAsyncWriteTest {
    private lateinit var logFile: File

    @Before
    fun setUp() {
        logFile = Files.createTempFile("ride-diag-test", ".log").toFile()
        RideDiagnostics.testOnlySetFile(logFile)
    }

    @After
    fun tearDown() {
        logFile.delete()
    }

    @Test
    fun `log() returns near-instantly instead of doing synchronous disk I-O`() {
        val start = System.nanoTime()
        repeat(50) { RideDiagnostics.log("test", "line $it") }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "50 log() calls took ${elapsedMs}ms -- should return near-instantly, not block on file I/O",
            elapsedMs < 500,
        )
    }

    @Test
    fun `writes from many threads all eventually land on disk`() {
        val perThread = 200
        val threadCount = 4
        val threads = (0 until threadCount).map { t ->
            Thread { repeat(perThread) { i -> RideDiagnostics.log("t$t", "msg $i") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        val expected = threadCount * perThread
        // 800 appendText calls (open/close each) on a loaded Windows machine took >5 s twice
        // (776/800, 582/800) — a slow disk, not lost writes; the assertion below still
        // demands every line.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && logFile.readLines().size < expected) {
            Thread.sleep(20)
        }
        val lines = logFile.readLines()
        assertTrue("expected $expected lines to have landed on disk, got ${lines.size}", lines.size == expected)
    }
}
