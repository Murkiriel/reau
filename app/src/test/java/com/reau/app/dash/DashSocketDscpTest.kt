package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket

/** The Wi-Fi priority marks of the dash's sockets. */
class DashSocketDscpTest {

    /** The WMM queue of an 802.11 user priority: 1-2 background, 0 and 3 best effort, 4-5 video, 6-7 voice. */
    private fun queue(userPriority: Int) = when (userPriority) {
        1, 2 -> "BK"
        0, 3 -> "BE"
        4, 5 -> "VI"
        else -> "VO"
    }

    /** Linux's older default: the user priority is the TOS byte's top three bits. */
    private fun legacyQueue(tos: Int) = queue(tos shr 5)

    /** RFC 8325, section 4.3, for the two marks used here (CS6 -> 6, AF41 -> 4). */
    private fun rfc8325Queue(tos: Int) = when (tos shr 2) {
        48 -> queue(6)
        34 -> queue(4)
        else -> error("not one of REau's marks: $tos")
    }

    @Test
    fun `control goes as CS6 and video as AF41, in the TOS byte`() {
        // DSCP is the TOS byte's top six bits: CS6 48 -> 0xC0, AF41 34 -> 0x88.
        assertEquals(0xC0, DashSocket.TOS_CONTROL)
        assertEquals(0x88, DashSocket.TOS_VIDEO)
    }

    @Test
    fun `control lands in the voice queue and video in the video queue, under both mappings`() {
        assertEquals("VO", legacyQueue(DashSocket.TOS_CONTROL))
        assertEquals("VO", rfc8325Queue(DashSocket.TOS_CONTROL))
        assertEquals("VI", legacyQueue(DashSocket.TOS_VIDEO))
        assertEquals("VI", rfc8325Queue(DashSocket.TOS_VIDEO))
    }

    @Test
    fun `EF would have shared the video queue under the older mapping`() {
        // Why not EF (46): its top three bits are 5, a video priority, like AF41's 4.
        assertEquals("VI", legacyQueue(46 shl 2))
    }

    @Test
    fun `mark is accepted on an open socket`() {
        // Windows takes the mark and ignores it (reads back 0), so the value read back is checked on Android, in
        // DashSocketDscpInstrumentedTest.
        DatagramSocket().use { socket -> assertTrue(DashSocket.mark(socket, DashSocket.TOS_VIDEO)) }
    }

    @Test
    fun `a closed socket is reported, not thrown`() {
        val socket = DatagramSocket().apply { close() }
        assertEquals(false, DashSocket.mark(socket, DashSocket.TOS_CONTROL))
    }
}
