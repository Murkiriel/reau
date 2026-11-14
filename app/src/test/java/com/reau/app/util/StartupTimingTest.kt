package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The start-up lines: `Application.onCreate`, `onResume` and `onStartCommand` say how long they took only when it passed 50 ms. */
class StartupTimingTest {
    private val written = mutableListOf<Pair<String, String>>()
    private val log: (String, String) -> Unit = { tag, msg -> written += tag to msg }

    @Test
    fun `49 and 50 ms write nothing, 51 ms does`() {
        assertFalse(StartupTiming.report("Application.onCreate", 49, log))
        assertFalse(StartupTiming.report("Application.onCreate", 50, log))
        assertTrue("nothing for 49 and 50 ms", written.isEmpty())
        assertTrue(StartupTiming.report("Application.onCreate", 51, log))
        assertEquals(listOf("startup" to "Application.onCreate took 51 ms"), written)
    }

    @Test
    fun `the line names what ran and how long it took`() {
        assertEquals("Application.onCreate took 83 ms", StartupTiming.line("Application.onCreate", 83))
        assertEquals(50L, StartupTiming.THRESHOLD_MS)
        assertFalse(StartupTiming.slow(0))
    }

    private fun clockOf(vararg readings: Long): () -> Long {
        val it = readings.iterator()
        return { it.next() }
    }

    @Test
    fun `timed gives back the block's value and writes one line only when the block was slow`() {
        val fast = StartupTiming.timed("MainActivity.onResume", clockOf(1_000, 1_049), log) { 7 }
        assertEquals(7, fast)
        assertTrue(written.isEmpty())
        val slow = StartupTiming.timed("MainActivity.onResume", clockOf(2_000, 2_051), log) { 8 }
        assertEquals(8, slow)
        assertEquals(listOf("startup" to "MainActivity.onResume took 51 ms"), written)
    }

    @Test
    fun `timed also reports a block that threw, and lets the exception go`() {
        val e = runCatching { StartupTiming.timed("ProjectionService.onStartCommand", clockOf(0, 90), log) { error("boom") } }.exceptionOrNull()
        assertEquals("boom", e?.message)
        assertEquals(listOf("startup" to "ProjectionService.onStartCommand took 90 ms"), written)
    }

    @Test
    fun `the three starts are timed where they run`() {
        fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()
        assertTrue(source("REauApp.kt").contains("StartupTiming.timed(\"Application.onCreate\")"))
        assertTrue(source("MainActivity.kt").contains("StartupTiming.timed(\"MainActivity.onResume\")"))
        assertTrue(source("service/ProjectionService.kt").contains("StartupTiming.timed(\"ProjectionService.onStartCommand\")"))
    }
}
