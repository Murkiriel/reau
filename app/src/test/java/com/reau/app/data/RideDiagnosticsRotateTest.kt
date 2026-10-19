package com.reau.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

private const val KEEP_FILES = RideDiagnostics.KEEP_FILES

class RideDiagnosticsRotateTest {
    @Test
    fun `keeps fifty ride logs`() {
        assertEquals(50, KEEP_FILES)
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ride-diagnostics-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Creates a ride-*.log file with a distinct, controlled lastModified time (oldest = index 0). */
    private fun rideLog(index: Int): File =
        File(dir, "ride-$index.log").apply {
            writeText("log $index")
            setLastModified(1_700_000_000_000L + index * 60_000L) // 1 minute apart, strictly increasing
        }

    @Test
    fun `does nothing when at or under the keep limit`() {
        repeat(KEEP_FILES) { rideLog(it) }
        RideDiagnostics.rotate(dir)
        assertEquals(KEEP_FILES, dir.listFiles()?.size)
    }

    @Test
    fun `deletes the oldest files beyond the keep limit, keeping the newest N`() {
        val files = (0 until KEEP_FILES + 5).map { rideLog(it) }
        RideDiagnostics.rotate(dir)

        val remaining = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(KEEP_FILES, remaining.size)

        // The newest KEEP_FILES (highest indices) must survive; the oldest 5 must be gone.
        val expectedSurvivors = files.takeLast(KEEP_FILES).map { it.name }.toSet()
        assertEquals(expectedSurvivors, remaining)
    }

    @Test
    fun `never touches files that don't match the ride- prefix or -log suffix`() {
        repeat(KEEP_FILES + 5) { rideLog(it) }
        val unrelated = File(dir, "other-file.txt").apply { writeText("keep me") }
        val wrongPrefix = File(dir, "notaride-99.log").apply { writeText("keep me too") }

        RideDiagnostics.rotate(dir)

        assertTrue(unrelated.exists())
        assertTrue(wrongPrefix.exists())
    }

    @Test
    fun `an empty directory is a no-op`() {
        RideDiagnostics.rotate(dir) // must not throw
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `a directory with a non-log file mixed in only counts matching logs toward the limit`() {
        repeat(KEEP_FILES) { rideLog(it) }
        File(dir, "notes.txt").writeText("irrelevant")

        RideDiagnostics.rotate(dir)

        // Exactly KEEP_FILES logs (at the limit) plus the untouched extra file.
        assertEquals(KEEP_FILES + 1, dir.listFiles()?.size)
    }
}
