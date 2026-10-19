package com.reau.app.dash.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class K1GPacketTest {

    @Test
    fun `build encodes outer length and seg count`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x06, 0x05, 0x01))
        // outer_len (bytes 0-1) must equal the actual packet size.
        val declaredLen = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
        assertEquals(pkt.size, declaredLen)
        // seg_count = 1 (fixed header) + 1 TLV.
        val segCount = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(2, segCount)
    }

    @Test
    fun `build with no TLVs still has the fixed header segment`() {
        val pkt = K1GPacket.build()
        val segCount = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(1, segCount)
    }

    @Test
    fun `build embeds the K1G magic at the documented offset`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x01, 0x02, 0x03))
        assertArrayEquals(
            byteArrayOf(0x4B, 0x31, 0x47, 0x20),
            pkt.copyOfRange(12, 16),
        )
    }

    @Test
    fun `build appends TLV type, sub, length and value in order`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0xAB, 0xCD, 0x11, 0x22, 0x33))
        // TLV starts right after the fixed header (12 bytes) + seq byte (1) = offset 17.
        val tlvStart = 17
        assertEquals(0xAB, pkt[tlvStart].toInt() and 0xFF)
        assertEquals(0xCD, pkt[tlvStart + 1].toInt() and 0xFF)
        val len = ((pkt[tlvStart + 2].toInt() and 0xFF) shl 8) or (pkt[tlvStart + 3].toInt() and 0xFF)
        assertEquals(3, len)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), pkt.copyOfRange(tlvStart + 4, tlvStart + 4 + len))
    }

    @Test
    fun `patchSeq writes the seq byte right after magic and does not mutate the input`() {
        val original = K1GPacket.build(K1GPacket.tlv(0x01, 0x01, 0x00))
        val patched = K1GPacket.patchSeq(original, 0x42)

        // Original untouched (patchSeq works on a copy).
        assertEquals(0x00, original[16].toInt() and 0xFF)
        assertEquals(0x42, patched[16].toInt() and 0xFF)
        assertEquals(patched.size, ((patched[0].toInt() and 0xFF) shl 8) or (patched[1].toInt() and 0xFF))
    }

    @Test
    fun `patchSeq wraps seq into a single byte`() {
        val pkt = K1GPacket.build()
        val patched = K1GPacket.patchSeq(pkt, 0x142) // 322 decimal, should wrap to 0x42
        assertEquals(0x42, patched[16].toInt() and 0xFF)
    }

    @Test
    fun `parseIncoming round-trips TLVs built for the outgoing format`() {
        // parseIncoming expects segments starting at offset 8 (shorter incoming header),
        // so hand-build a minimal incoming-shaped packet instead of using build().
        val tlvBytes = byteArrayOf(
            0x05, 0x02, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte(), // TLV 1: type 5 sub 2 len 2
            0x06, 0x01, 0x00, 0x01, 0xCC.toByte(),                // TLV 2: type 6 sub 1 len 1
        )
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00)
        val data = header + tlvBytes

        val tlvs = K1GPacket.parseIncoming(data)

        assertEquals(2, tlvs.size)
        assertEquals(0x05, tlvs[0].type)
        assertEquals(0x02, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), tlvs[0].value)
        assertEquals(0x06, tlvs[1].type)
        assertEquals(0x01, tlvs[1].sub)
        assertArrayEquals(byteArrayOf(0xCC.toByte()), tlvs[1].value)
    }

    @Test
    fun `parseIncoming on too-short data returns no TLVs instead of throwing`() {
        assertTrue(K1GPacket.parseIncoming(byteArrayOf(0x01, 0x02)).isEmpty())
        assertTrue(K1GPacket.parseIncoming(ByteArray(0)).isEmpty())
    }

    @Test
    fun `parseIncoming clamps a truncated TLV value instead of throwing`() {
        // Declares a 2-byte segment count but only supplies a TLV header claiming
        // len=100 with just 1 byte of actual value data following.
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        val truncatedTlv = byteArrayOf(0x01, 0x01, 0x00, 0x64, 0xFF.toByte()) // len=100, only 1B present
        val tlvs = K1GPacket.parseIncoming(header + truncatedTlv)

        assertEquals(1, tlvs.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), tlvs[0].value) // clamped to what's actually there
    }

    @Test
    fun `tlv with int varargs packs each value as a single byte`() {
        val t = K1GPacket.tlv(0x01, 0x02, 0x100 + 0x0A) // 0x10A truncates to 0x0A
        assertArrayEquals(byteArrayOf(0x0A), t.value)
    }

    @Test
    fun `hexToBytes decodes with and without spaces`() {
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), "DEAD".hexToBytes())
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()), "DE AD BE".hexToBytes())
    }
}
