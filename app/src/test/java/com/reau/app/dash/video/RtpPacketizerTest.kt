package com.reau.app.dash.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketizerTest {

    private fun nalOf(size: Int, header: Int = 0x65 /* NRI=3, type=5 (IDR) */): ByteArray {
        val nal = ByteArray(size)
        nal[0] = header.toByte()
        return nal
    }

    @Test
    fun `a NAL at or under the max payload is sent as a single unfragmented packet`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }

        val nal = nalOf(500)
        packetizer.packetize(nal, endOfAU = true, wallClockMs = 0)

        assertEquals(1, packets.size)
        val pkt = packets[0]
        assertEquals(12 + 500, pkt.size)
        // RTP header byte 0: version 2, no padding/extension/CSRC -> 0x80.
        assertEquals(0x80, pkt[0].toInt() and 0xFF)
        // Marker bit set (endOfAU=true) + payload type 96.
        assertEquals((0x80 or 96), pkt[1].toInt() and 0xFF)
        // Payload (from byte 12) is exactly the original NAL, unmodified — no FU-A wrapping.
        assertTrue(pkt.copyOfRange(12, pkt.size).contentEquals(nal))
    }

    @Test
    fun `endOfAU false never sets the marker bit even for a single packet`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }
        packetizer.packetize(nalOf(100), endOfAU = false, wallClockMs = 0)

        assertEquals(0, packets[0][1].toInt() and 0x80)
    }

    @Test
    fun `a NAL over the max payload is fragmented into FU-A packets`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }

        // header: F=0, NRI=3 (0x60), type=5 (IDR) -> 0x65.
        val nal = nalOf(3000, header = 0x65)
        packetizer.packetize(nal, endOfAU = true, wallClockMs = 0)

        assertEquals(3, packets.size) // per the fragmentation walk-through: 1378 + 1378 + 243

        for (pkt in packets) {
            assertTrue(pkt.size <= 12 + 1380)
        }

        // FU indicator (payload byte 0): NRI preserved from the original header, type forced to 28.
        val expectedFuIndicator = ((0x65 and 0xE0) or 28)
        for (pkt in packets) {
            assertEquals(expectedFuIndicator, pkt[12].toInt() and 0xFF)
        }

        // FU header (payload byte 1): S=1 only on first, E=1 only on last, NAL type preserved (5).
        val fuHeaders = packets.map { it[13].toInt() and 0xFF }
        assertEquals(0x80 or 5, fuHeaders[0])       // S=1, E=0, type=5
        assertEquals(5, fuHeaders[1])                // S=0, E=0, type=5 (middle fragment)
        assertEquals(0x40 or 5, fuHeaders[2])        // S=0, E=1, type=5

        // Marker bit: only the RTP packet carrying the LAST fragment of the AU.
        assertFalse(packets[0][1].toInt() and 0x80 != 0)
        assertFalse(packets[1][1].toInt() and 0x80 != 0)
        assertTrue(packets[2][1].toInt() and 0x80 != 0)
    }

    @Test
    fun `fragmented payload bytes reassemble to the original NAL body (minus the header byte)`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }

        val nal = ByteArray(3000) { (it and 0xFF).toByte() }
        nal[0] = 0x65
        packetizer.packetize(nal, endOfAU = true, wallClockMs = 0)

        val reassembled = packets.flatMap { it.drop(14) } // skip 12B RTP + 2B FU header
        assertEquals(nal.drop(1), reassembled) // original NAL header byte is replaced by the FU header
    }

    @Test
    fun `sequence number increments by exactly one across consecutive packets`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }

        packetizer.packetize(nalOf(100), endOfAU = true, wallClockMs = 0)
        packetizer.packetize(nalOf(100), endOfAU = true, wallClockMs = 10)

        fun seqOf(pkt: ByteArray) = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        val seq0 = seqOf(packets[0])
        val seq1 = seqOf(packets[1])
        assertEquals((seq0 + 1) and 0xFFFF, seq1)
    }

    @Test
    fun `timestamp advances by wall-clock delta times the 90kHz clock`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }

        packetizer.packetize(nalOf(100), endOfAU = true, wallClockMs = 0)
        packetizer.packetize(nalOf(100), endOfAU = true, wallClockMs = 100)

        fun tsOf(pkt: ByteArray): Long =
            ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
                ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)

        val delta = (tsOf(packets[1]) - tsOf(packets[0])) and 0xFFFFFFFFL
        assertEquals(100L * 90L, delta)
    }

    @Test
    fun `all RTP packets share the same SSRC`() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packets.add(it) }
        packetizer.packetize(nalOf(3000), endOfAU = true, wallClockMs = 0)

        fun ssrcOf(pkt: ByteArray) = pkt.copyOfRange(8, 12).toList()
        val ssrcs = packets.map { ssrcOf(it) }.distinct()
        assertEquals(1, ssrcs.size)
    }
}
