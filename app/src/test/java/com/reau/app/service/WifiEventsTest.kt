package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own word on its Wi-Fi, for the ride log (item 21 of the 11/11/2026 list): states and counts, never a name or an address. */
class WifiEventsTest {
    @Test
    fun `the radio is told by its name`() {
        assertEquals("wifi radio enabled", WifiEvents.radioLine("enabled"))
    }

    @Test
    fun `the supplicant's states are told once each change, and a scan is no event`() {
        val s = WifiEvents.Supplicant()
        assertEquals("wifi supplicant: ASSOCIATING", s.line("ASSOCIATING", 0))
        assertNull("the same state again", s.line("ASSOCIATING", 0))
        assertEquals("wifi supplicant: COMPLETED", s.line("COMPLETED", 0))
        assertNull("a scan", s.line("SCANNING", 0))
        assertEquals("wifi supplicant: DISCONNECTED", s.line("DISCONNECTED", 0))
        assertEquals("wifi supplicant: COMPLETED", s.line("COMPLETED", 0))
    }

    @Test
    fun `an authentication error is told with its state, even when the state did not change`() {
        val s = WifiEvents.Supplicant()
        s.line("FOUR_WAY_HANDSHAKE", 0)
        assertEquals("wifi supplicant: FOUR_WAY_HANDSHAKE (authentication failed)", s.line("FOUR_WAY_HANDSHAKE", 1))
        assertEquals("wifi supplicant: INACTIVE (error 7)", s.line("INACTIVE", 7))
    }

    @Test
    fun `the network state is told once each change`() {
        val n = WifiEvents.NetworkState()
        assertEquals("wifi network state: CONNECTED", n.line("CONNECTED"))
        assertNull(n.line("CONNECTED"))
        assertEquals("wifi network state: DISCONNECTED", n.line("DISCONNECTED"))
        assertEquals("wifi network state: ?", n.line(null))
    }

    @Test
    fun `the networks that come and go are counted, with the id kept out of the line`() {
        val w = WifiEvents.Networks()
        assertEquals("wifi network up (1 now)", w.available(101))
        assertEquals("wifi network up (2 now)", w.available(202))
        assertEquals("wifi network losing in 5000 ms (one of 2)", w.losing(101, 5000))
        assertEquals("wifi network lost (1 left)", w.lost(101))
        assertEquals("a loss REau never heard of is said so", "wifi network lost (1 left, not one REau had heard)", w.lost(999))
        assertEquals("wifi network lost (0 left)", w.lost(202))
        assertEquals("wifi network request unavailable", WifiEvents.Networks.UNAVAILABLE)
    }

    @Test
    fun `no line carries a name, an address or a signal`() {
        val lines = listOf(
            WifiEvents.radioLine("enabled"), WifiEvents.Supplicant().line("COMPLETED", 0).orEmpty(), WifiEvents.NetworkState().line("CONNECTED").orEmpty(),
            WifiEvents.Networks().available(5),
        )
        for (line in lines) assertTrue(line, !Regex("""\d+\.\d+\.\d+\.\d+|([0-9a-f]{2}:){5}|dBm|RE_""").containsMatchIn(line))
    }
}
