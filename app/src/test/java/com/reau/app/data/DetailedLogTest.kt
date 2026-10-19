package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detailed log, behind the "Registro detalhado" switch (off by default), with a ceiling of lines a second so it
 * never drowns the file.
 */
class DetailedLogTest {
    private var nowMs = 10_000L
    private val written = mutableListOf<Pair<String, String>>()
    private val log = DetailedLogger(maxPerSecond = 3, nowMs = { nowMs }) { tag, line -> written += tag to line }

    @Test
    fun `switched off, nothing is written and the message is not even built`() {
        var built = 0
        log.offer("dash") { built++; "tx 0556 55" }
        assertEquals(emptyList<Pair<String, String>>(), written)
        assertEquals(0, built)
    }

    @Test
    fun `switched on, each line goes under the detail tag with what it is about`() {
        log.enabled = true
        log.offer("dash") { "tx 0556 55" }
        assertEquals(listOf("detail" to "dash tx 0556 55"), written)
    }

    @Test
    fun `over the ceiling in a second the rest is dropped and counted, and said when the next second starts`() {
        log.enabled = true
        repeat(5) { i -> log.offer("aa") { "rx $i" } }
        assertEquals(listOf("aa rx 0", "aa rx 1", "aa rx 2"), written.map { it.second })
        nowMs += 1_000
        log.offer("aa") { "rx 5" }
        assertEquals(
            listOf("aa rx 0", "aa rx 1", "aa rx 2", "2 lines over the ceiling of 3 a second dropped", "aa rx 5"),
            written.map { it.second },
        )
    }

    @Test
    fun `the ceiling is per second, not per session`() {
        log.enabled = true
        repeat(3) { log.offer("aa") { "a" } }
        nowMs += 999
        log.offer("aa") { "still the same second" }
        nowMs += 1
        log.offer("aa") { "a new second" }
        val lines = written.map { it.second }
        assertTrue(lines.toString(), "aa a new second" in lines && "aa still the same second" !in lines)
    }
}
