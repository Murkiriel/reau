package com.reau.app.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather after a failed request waits 1, 2, 4, 8 and then 15 minutes (the ceiling), and starts over at the first answer that
 * works. Before, a failure was tried again every minute for ever.
 */
class WeatherBackoffTest {
    private val minute = 60_000L

    @Test
    fun `the waits grow 1 2 4 8 then stay at 15 minutes`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L, 15L), (1..7).map { WeatherBackoff.waitMs(it) / minute })
    }

    @Test
    fun `with no failure pending a request may always go`() {
        val backoff = WeatherBackoff()
        assertTrue(backoff.mayTry(1))
        assertEquals(0, backoff.failures)
    }

    @Test
    fun `after a failure the next request waits out the step, to the millisecond`() {
        val backoff = WeatherBackoff()
        assertEquals(1 * minute, backoff.failed(nowMs = 10_000))
        assertFalse(backoff.mayTry(10_000 + minute - 1))
        assertTrue(backoff.mayTry(10_000 + minute))
        assertEquals(2 * minute, backoff.failed(nowMs = 10_000 + minute))
        assertFalse(backoff.mayTry(10_000 + 2 * minute))
        assertTrue(backoff.mayTry(10_000 + 3 * minute))
        assertEquals(2, backoff.failures)
    }

    @Test
    fun `a rider's minute-by-minute look is held back, then let through at the ceiling every 15 minutes`() {
        val backoff = WeatherBackoff()
        var now = 0L
        val tries = mutableListOf<Long>()
        repeat(60) { minuteNumber ->
            now = minuteNumber * minute
            if (backoff.mayTry(now)) {
                tries += minuteNumber.toLong()
                backoff.failed(now)
            }
        }
        // 0, +1, +2, +4, +8, +15, +15 ...: 0 1 3 7 15 30 45
        assertEquals(listOf(0L, 1L, 3L, 7L, 15L, 30L, 45L), tries)
    }

    @Test
    fun `the first answer that works starts the waits over`() {
        val backoff = WeatherBackoff()
        repeat(6) { backoff.failed(0) }
        assertFalse(backoff.mayTry(minute))
        backoff.succeeded()
        assertEquals(0, backoff.failures)
        assertTrue(backoff.mayTry(0))
        assertEquals(1 * minute, backoff.failed(0))
    }

    @Test
    fun `a reset forgets the failures like a success`() {
        val backoff = WeatherBackoff()
        backoff.failed(0)
        backoff.reset()
        assertTrue(backoff.mayTry(0))
        assertEquals(0, backoff.failures)
    }
}
