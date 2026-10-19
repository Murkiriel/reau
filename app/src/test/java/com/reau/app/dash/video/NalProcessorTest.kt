package com.reau.app.dash.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NalProcessorTest {

    private val startCode4 = byteArrayOf(0, 0, 0, 1)
    private val startCode3 = byteArrayOf(0, 0, 1)

    private fun annexB(vararg nals: ByteArray, useShortStartCodeFor: Set<Int> = emptySet()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        nals.forEachIndexed { i, nal ->
            out.write(if (i in useShortStartCodeFor) startCode3 else startCode4)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun nal(type: Int, vararg rest: Int): ByteArray {
        val header = ((0x60) or (type and 0x1F)).toByte() // NRI=3
        return byteArrayOf(header) + rest.map { it.toByte() }.toByteArray()
    }

    @Test
    fun `SEI and AUD are discarded, never reaching the callback`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { nal, _ -> received.add(nal) }

        processor.process(annexB(nal(6, 0x01, 0x02), nal(9, 0xF0)))

        assertTrue(received.isEmpty())
    }

    @Test
    fun `a NAL type outside the forwarded ranges is silently dropped`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { nal, _ -> received.add(nal) }

        processor.process(annexB(nal(13, 0x01))) // reserved/unhandled type
        assertTrue(received.isEmpty())
    }

    @Test
    fun `ordinary slice types 1-4 and 10-12 are forwarded as-is, not marked keyframe`() {
        val received = mutableListOf<Pair<ByteArray, Boolean>>()
        val processor = NalProcessor { n, isKey -> received.add(n to isKey) }

        val slice = nal(1, 0xAA, 0xBB)
        processor.process(annexB(slice))

        assertEquals(1, received.size)
        assertArrayEquals(slice, received[0].first)
        assertEquals(false, received[0].second)
    }

    @Test
    fun `IDR with no cached SPS-PPS is withheld and counted`() {
        val received = mutableListOf<Pair<ByteArray, Boolean>>()
        val processor = NalProcessor { n, isKey -> received.add(n to isKey) }

        processor.process(annexB(nal(5, 0x01, 0x02, 0x03)))
        processor.process(annexB(nal(5, 0x04)))

        assertTrue(received.isEmpty())
        assertEquals(2, processor.withheldIdr)
    }

    @Test
    fun `an IDR is sent again once SPS and PPS have arrived, and the count stays`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { n, _ -> received.add(n) }

        processor.process(annexB(nal(5, 0x01)))
        processor.process(annexB(byteArrayOf(0x67, 0x42, 0x00, 0x29), byteArrayOf(0x68, 0x01), nal(5, 0x02)))

        assertEquals(1, received.size)
        assertEquals(1, processor.withheldIdr)
    }

    @Test
    fun `IDR after SPS and PPS are cached is bundled as SPS + PPS + IDR with start codes`() {
        val received = mutableListOf<Pair<ByteArray, Boolean>>()
        val processor = NalProcessor { n, isKey -> received.add(n to isKey) }

        // SPS with the dash-compatible shape (67 42 00 29) -- normalizeSpsForDash
        // should leave byte[2] alone since it's already 0x00.
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x29, 0xAB.toByte())
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = nal(5, 0x11, 0x22)

        processor.process(annexB(sps, pps, idr))

        assertEquals(1, received.size) // SPS/PPS are cached, not forwarded on their own
        val (bundle, isKey) = received[0]
        assertEquals(true, isKey)
        assertArrayEquals(sps + startCode4 + pps + startCode4 + idr, bundle)
    }

    @Test
    fun `SPS with the MediaCodec constraint byte is normalized to the dash-compatible shape`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { n, _ -> received.add(n) }

        // MediaCodec emits 67 42 C0 29 ... ; the dash firmware only whitelists 67 42 00 29 ...
        val mediaCodecSps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x29, 0x55)
        val expectedNormalizedSps = byteArrayOf(0x67, 0x42, 0x00, 0x29, 0x55)
        val pps = byteArrayOf(0x68, 0x01)
        val idr = nal(5, 0x9A)

        processor.process(annexB(mediaCodecSps, pps, idr))

        val bundle = received.single()
        assertArrayEquals(expectedNormalizedSps, bundle.copyOfRange(0, 5))
    }

    @Test
    fun `an SPS not matching the 67 42 __ 29 shape is passed through unmodified`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { n, _ -> received.add(n) }

        val oddSps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11) // profile 0x64 (High), not 0x42
        val pps = byteArrayOf(0x68, 0x01)
        val idr = nal(5, 0x01)

        processor.process(annexB(oddSps, pps, idr))

        val bundle = received.single()
        assertArrayEquals(oddSps, bundle.copyOfRange(0, 5))
    }

    @Test
    fun `split handles both 3-byte and 4-byte Annex-B start codes in the same stream`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { n, _ -> received.add(n) }

        val a = nal(1, 0x01)
        val b = nal(2, 0x02)
        processor.process(annexB(a, b, useShortStartCodeFor = setOf(1))) // b uses a 3-byte start code

        assertEquals(2, received.size)
        assertArrayEquals(a, received[0])
        assertArrayEquals(b, received[1])
    }

    @Test
    fun `a second IDR reuses the same cached SPS-PPS without needing them resent`() {
        val received = mutableListOf<ByteArray>()
        val processor = NalProcessor { n, _ -> received.add(n) }

        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x29)
        val pps = byteArrayOf(0x68, 0x01)
        val idr1 = nal(5, 0x01)
        val idr2 = nal(5, 0x02)

        processor.process(annexB(sps, pps, idr1))
        processor.process(annexB(idr2)) // no SPS/PPS this time -- must still bundle from cache

        assertEquals(2, received.size)
        assertArrayEquals(sps + startCode4 + pps + startCode4 + idr2, received[1])
    }
}
