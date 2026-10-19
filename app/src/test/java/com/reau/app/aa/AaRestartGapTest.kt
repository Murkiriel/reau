package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Switching the projection off and on quickly brought Android Auto down 7 times in a minute, and once with
 * "Already connected". The first dial after a stop waits until a
 * second has passed since the stop.
 */
class AaRestartGapTest {
    @Test
    fun `no stop before, no wait`() {
        assertEquals(0L, AaRestartGap.delayMs(nowMs = 5_000, stoppedAtMs = null))
    }

    @Test
    fun `a stop 300 ms ago waits 700 ms more, one a second ago waits nothing`() {
        assertEquals(700L, AaRestartGap.delayMs(nowMs = 10_300, stoppedAtMs = 10_000))
        assertEquals(0L, AaRestartGap.delayMs(nowMs = 11_000, stoppedAtMs = 10_000))
        assertEquals(0L, AaRestartGap.delayMs(nowMs = 60_000, stoppedAtMs = 10_000))
    }
}
