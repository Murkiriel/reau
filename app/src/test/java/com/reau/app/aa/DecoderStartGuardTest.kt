package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VideoDecoder.decode()` tried to start a decoder on every picture while there was none, so a codec that kept
 * refusing to configure was created again 30 times a second. The guard spaces the tries out and, after two failures in a
 * row, asks for the software decoder.
 */
class DecoderStartGuardTest {
    private val guard = DecoderStartGuard()

    @Test
    fun `a decoder that never failed may start at any time`() {
        assertTrue(guard.mayTry(0L))
        assertTrue(guard.mayTry(1_000_000L))
        assertFalse(guard.useSoftware)
    }

    @Test
    fun `after a failure the next try waits, then is allowed`() {
        guard.failed(10_000L)
        assertFalse("the picture 33 ms later must not start another codec", guard.mayTry(10_033L))
        assertFalse(guard.mayTry(10_000L + DecoderStartGuard.backoffMs(1) - 1))
        assertTrue(guard.mayTry(10_000L + DecoderStartGuard.backoffMs(1)))
    }

    @Test
    fun `the wait grows with the failures in a row and stops at ten seconds`() {
        assertEquals(250L, DecoderStartGuard.backoffMs(1))
        assertEquals(1_000L, DecoderStartGuard.backoffMs(2))
        assertEquals(4_000L, DecoderStartGuard.backoffMs(3))
        assertEquals(10_000L, DecoderStartGuard.backoffMs(4))
        assertEquals(10_000L, DecoderStartGuard.backoffMs(40))
    }

    @Test
    fun `the software decoder is asked for from the second failure in a row`() {
        guard.failed(0L)
        assertFalse(guard.useSoftware)
        guard.failed(1_000L)
        assertTrue(guard.useSoftware)
        assertEquals(DecoderStartGuard.SOFTWARE_AFTER, 2)
    }

    @Test
    fun `a start that works, or a new surface, begins again`() {
        guard.failed(0L)
        guard.failed(1_000L)
        guard.worked()
        assertTrue(guard.mayTry(1_001L))
        assertFalse(guard.useSoftware)
        assertEquals(0, guard.failuresInARow)

        guard.failed(2_000L)
        guard.reset()
        assertTrue(guard.mayTry(2_001L))
    }

    @Test
    fun `the low-latency mode is asked for on the first try only, a decoder that refuses it is tried again without`() {
        assertTrue(guard.lowLatency)
        guard.failed(0L)
        assertFalse(guard.lowLatency)
        guard.worked()
        assertTrue("a start that worked asks for it again", guard.lowLatency)
    }

    @Test
    fun `a decoder that started and stalled once is still the hardware one, twice it is the software one`() {
        assertFalse("the first stall does not say it", guard.stalled())
        assertFalse(guard.useSoftware)
        assertTrue("the second says it, once", guard.stalled())
        assertTrue(guard.useSoftware)
        assertFalse("a third is no news", guard.stalled())
        assertTrue(guard.useSoftware)
        assertEquals(DecoderStartGuard.STALLS_BEFORE_SOFTWARE, 2)
    }

    @Test
    fun `a start that works forgives the failures but not the stalls`() {
        guard.stalled()
        guard.stalled()
        guard.failed(0L)
        guard.worked()
        assertEquals(0, guard.failuresInARow)
        assertTrue("the restart that follows each stall starts well; it must not clear the count", guard.useSoftware)
    }

    @Test
    fun `a new surface forgives the stalls as well`() {
        guard.stalled()
        guard.stalled()
        guard.reset()
        assertFalse(guard.useSoftware)
        assertFalse("and counts from the beginning", guard.stalled())
        assertFalse(guard.useSoftware)
    }

    @Test
    fun `start failures and stalls are counted apart`() {
        guard.failed(0L)
        guard.stalled()
        assertFalse("one failure and one stall make no software decoder", guard.useSoftware)
        guard.stalled()
        assertTrue(guard.useSoftware)
        guard.reset()
        guard.stalled()
        guard.failed(0L)
        guard.failed(1_000L)
        assertTrue("two failures in a row, whatever the stalls", guard.useSoftware)
    }
}
