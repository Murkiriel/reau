package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For 11 minutes Android Auto's server accepted the connection and never answered the handshake, 49 times, until
 * its own process died; REau said nothing. Three handshakes in a row with no answer say so.
 */
class HandshakeStreakTest {
    @Test
    fun `the third handshake in a row with no answer says so`() {
        val s = HandshakeStreak()
        assertFalse(s.after(handshook = false))
        assertFalse(s.after(handshook = false))
        assertTrue(s.after(handshook = false))
        assertTrue("and keeps saying so", s.after(handshook = false))
    }

    @Test
    fun `an answered handshake starts the count again`() {
        val s = HandshakeStreak()
        s.after(false)
        s.after(false)
        assertFalse(s.after(true))
        assertFalse(s.after(false))
        assertFalse(s.after(false))
        assertEquals(2, s.inARow)
    }

    @Test
    fun `after REau was ended mid-session the first handshake with no answer says so, until one is answered`() {
        // A ride: after each install Android Auto answered nothing for three rounds, 33 s, until its server was restarted.
        val s = HandshakeStreak(quick = true)
        assertTrue(s.after(handshook = false))
        assertFalse(s.after(handshook = true))
        assertFalse("then three again", s.after(handshook = false))
        assertFalse(s.after(handshook = false))
        assertTrue(s.after(handshook = false))
    }
}
