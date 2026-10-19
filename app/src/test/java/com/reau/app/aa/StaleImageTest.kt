package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frozen Android Auto looks alive on the dash (the compositor repeats the last picture). Measured on rides in use: with the route's speed at 15 km/h or more there was not one window of 5 s without a new
 * picture; stopped, or with no route, Android Auto goes quiet for up to a minute with a map that does not change.
 * So only a moving bike with no new picture for 15 s says "stuck".
 */
class StaleImageTest {
    private val riding = 60.0

    /** Feeds one check a second with [units] fixed and returns the first verdict told, as (second, verdict), or null. */
    private fun silence(s: StaleImage, from: Long, seconds: Int, units: Long, kmh: Double?): Pair<Int, Boolean>? {
        for (i in 0..seconds) s.check(from + i * 1_000L, units, kmh)?.let { return i to it }
        return null
    }

    @Test
    fun `a moving bike with no new picture for 15 s is stuck, said once`() {
        val s = StaleImage()
        assertNull(s.check(0L, 100, riding))
        assertNull("not yet at 14 s", s.check(14_000L, 100, riding))
        assertEquals(true, s.check(15_000L, 100, riding))
        assertNull("said once", s.check(16_000L, 100, riding))
        assertTrue(s.isStale)
    }

    @Test
    fun `a new picture ends it, and counts again from there`() {
        val s = StaleImage()
        s.check(0L, 100, riding)
        s.check(15_000L, 100, riding)
        assertEquals(false, s.check(16_000L, 101, riding))
        assertFalse(s.isStale)
        assertNull("14 s after the new picture it is alive", s.check(29_000L, 101, riding))
        assertEquals(true, s.check(31_000L, 101, riding))
    }

    @Test
    fun `a stopped bike or one with no route is never stuck, however long Android Auto is quiet`() {
        val s = StaleImage()
        assertNull(silence(s, 0L, 600, units = 100, kmh = 0.0))
        assertNull(silence(s, 700_000L, 600, units = 100, kmh = null))
        assertNull("under 15 km/h is not moving", silence(s, 1_400_000L, 600, units = 100, kmh = 14.0))
    }

    @Test
    fun `the silence counts from the moment the bike began to move, not from the last picture before the stop`() {
        val s = StaleImage()
        // A minute stopped at a light with a map that does not change.
        assertNull(silence(s, 0L, 60, units = 100, kmh = 0.0))
        // It sets off at 60 s: a new picture is due, but 14 s later it is not yet stuck.
        assertNull(s.check(60_000L, 100, riding))
        assertNull(s.check(74_000L, 100, riding))
        assertEquals(true, s.check(75_000L, 100, riding))
    }

    @Test
    fun `stopping again clears it at once`() {
        val s = StaleImage()
        s.check(0L, 100, riding)
        s.check(15_000L, 100, riding)
        assertEquals(false, s.check(16_000L, 100, 0.0))
    }

    @Test
    fun `a session with no picture at all is the other warning's, not this one's`() {
        val s = StaleImage()
        assertNull(silence(s, 0L, 120, units = 0, kmh = riding))
        assertFalse(s.isStale)
    }
}
