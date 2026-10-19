package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ride: after Android Auto's server was restarted its own encoder stuck, every session opened with no video unit and
 * the dash showed black, with nothing on the phone saying why. Ten seconds of an open session with no unit say so.
 */
class VideoSilenceTest {
    @Test
    fun `an open session with no picture for 10 s is silent, said once`() {
        val s = VideoSilence()
        assertNull(s.check(nowMs = 1_000L, units = 0))
        assertNull("not yet at 9,9 s", s.check(10_900L, 0))
        assertEquals(true, s.check(11_000L, 0))
        assertNull("said once", s.check(12_000L, 0))
        assertTrue(s.isSilent)
    }

    @Test
    fun `the first unit ends the silence`() {
        val s = VideoSilence()
        s.check(0L, 0)
        s.check(10_000L, 0)
        assertEquals(false, s.check(11_000L, 3))
        assertFalse(s.isSilent)
    }

    @Test
    fun `a session with its picture is never silent`() {
        val s = VideoSilence()
        s.check(0L, 0)
        assertNull(s.check(500L, 2))
        assertNull(s.check(60_000L, 1_800))
        assertFalse(s.isSilent)
    }

    @Test
    fun `the quick restart rule is for the first projection of the process only`() {
        val first = FirstOfProcess()
        assertTrue(first.take())
        assertFalse("a Desligar and Ligar later is no fresh start", first.take())
    }
}
