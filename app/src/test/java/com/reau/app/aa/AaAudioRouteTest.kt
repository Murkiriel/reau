package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the phone's own sound ended up (the sound that REau plays on the phone can be captured back by Android
 * Auto and go round and round). Android Auto's capture is a "remote submix" device; REau only plays on the phone if
 * the system did not send its track there.
 */
class AaAudioRouteTest {
    @Test
    fun `the remote submix is the loop, the phone's own outputs are not`() {
        assertTrue(AaAudioRoute.isTheLoop(25)) // AudioDeviceInfo.TYPE_REMOTE_SUBMIX
        assertFalse(AaAudioRoute.isTheLoop(2)) // built-in speaker
        assertFalse(AaAudioRoute.isTheLoop(3)) // wired headset
        assertFalse(AaAudioRoute.isTheLoop(7)) // Bluetooth SCO
        assertFalse(AaAudioRoute.isTheLoop(8)) // Bluetooth A2DP: the rider's headset
        assertFalse(AaAudioRoute.isTheLoop(27)) // BLE headset
    }

    @Test
    fun `a route not known yet is not a reason to stop`() {
        assertFalse(AaAudioRoute.isTheLoop(null))
    }

    @Test
    fun `the check is made after a few chunks, when the route is known, and again a few times in case it was not yet`() {
        assertEquals(false, AaAudioRoute.checkNow(written = 1))
        assertEquals(false, AaAudioRoute.checkNow(written = AaAudioRoute.CHECK_AFTER - 1))
        assertEquals(listOf(8, 16, 32, 64), (1..200).filter { AaAudioRoute.checkNow(written = it) })
    }

    @Test
    fun `the usage the phone's sound plays with is one Android Auto's capture does not match`() {
        // Android Auto's mixes match USAGE_MEDIA (1) and USAGE_GAME (14), and, once the speech sink is announced, USAGE_ASSISTANT (16) and
        // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (12) (dumpsys audio): the guide's voice played as navigation was
        // captured back.
        val matched = setOf(1, 14, 16, 12)
        for (stream in AaAudioFeed.Stream.entries) assertFalse("$stream", AaAudioRoute.usageOf(stream) in matched)
    }
}
