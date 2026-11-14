package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The count of how long the bike network has been lost, which [DashWifiManager] keeps for [ReconnectPace]'s long pause. */
class AbsenceClockTest {
    private val minute = 60_000L
    private val SHORT_PAUSE_MS = 4_000L // the 4 s pause of a reconnection (RECONNECT_DELAY, in ReconnectPace.kt)

    @Test
    fun `the pause is the short one until the dash has been away for 3 minutes, then the long one`() {
        val clock = AbsenceClock()
        assertEquals("nothing lost yet", SHORT_PAUSE_MS, clock.pauseMs(10 * minute))
        clock.lost(nowMs = 1_000)
        assertEquals(SHORT_PAUSE_MS, clock.pauseMs(1_000 + 3 * minute - 1))
        assertEquals(ReconnectPace.BACKOFF_PAUSE_MS, clock.pauseMs(1_000 + 3 * minute))
    }

    @Test
    fun `a second loss while it is still away does not start the count over`() {
        val clock = AbsenceClock()
        clock.lost(nowMs = 1_000)
        clock.lost(nowMs = 2 * minute) // the request failed and the loss was told again
        assertEquals("counted from the first loss", ReconnectPace.BACKOFF_PAUSE_MS, clock.pauseMs(1_000 + 3 * minute))
    }

    @Test
    fun `the network coming back, or a new connection, starts the count over`() {
        val clock = AbsenceClock()
        clock.lost(nowMs = 1_000)
        assertEquals(ReconnectPace.BACKOFF_PAUSE_MS, clock.pauseMs(1_000 + 5 * minute))
        clock.back()
        assertEquals(SHORT_PAUSE_MS, clock.pauseMs(1_000 + 5 * minute))
        clock.lost(nowMs = 10 * minute)
        assertEquals("a new absence counts from its own start", SHORT_PAUSE_MS, clock.pauseMs(10 * minute + minute))
    }

    @Test
    fun `the log line of the long pause is due once per absence`() {
        val clock = AbsenceClock()
        val start = 1_000L // the monotonic clock is never 0 (0 means "not lost" in the clock)
        clock.lost(nowMs = start)
        assertFalse("not yet", clock.backoffStarted(start + 2 * minute))
        assertTrue(clock.backoffStarted(start + 3 * minute))
        assertFalse("said once", clock.backoffStarted(start + 4 * minute))
        clock.back()
        clock.lost(nowMs = start + 10 * minute)
        assertFalse(clock.backoffStarted(start + 11 * minute))
        assertTrue("and again in the next absence", clock.backoffStarted(start + 13 * minute))
    }
}
