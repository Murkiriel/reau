package com.reau.app.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The units of an Annex-B H.264 buffer: where they start, what they are, and whether a frame is a key one. */
class AnnexBTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val sps = bytes(0x67, 0x42, 0x00, 0x1F, 0xAB)
    private val pps = bytes(0x68, 0xCE, 0x3C, 0x80)
    private val idr = bytes(0x65, 0x88, 0x84, 0x00, 0x10)
    private val slice = bytes(0x41, 0x9A, 0x24, 0x6C)

    private val four = bytes(0, 0, 0, 1)
    private val three = bytes(0, 0, 1)

    @Test
    fun `units split at 4 and at 3 byte start codes`() {
        val data = four + sps + four + pps + three + idr
        val units = AnnexB.split(data)
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
    }

    @Test
    fun `the zero byte of a 4 byte code is not part of the unit before it`() {
        val units = AnnexB.split(three + sps + four + pps)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
    }

    @Test
    fun `no start code, or nothing, is no unit`() {
        assertTrue(AnnexB.split(ByteArray(0)).isEmpty())
        assertTrue(AnnexB.split(bytes(0x65, 0x88, 0x84)).isEmpty())
        assertTrue(AnnexB.split(four).isEmpty())
    }

    @Test
    fun `the type is the low five bits of the first byte`() {
        assertEquals(7, AnnexB.type(sps))
        assertEquals(8, AnnexB.type(pps))
        assertEquals(5, AnnexB.type(idr))
        assertEquals(1, AnnexB.type(slice))
    }

    @Test
    fun `a buffer with an IDR is a key frame, one with only slices is not`() {
        assertTrue(AnnexB.hasIdr(four + sps + four + pps + four + idr))
        assertTrue(AnnexB.hasIdr(four + idr))
        assertFalse(AnnexB.hasIdr(four + slice))
        assertFalse(AnnexB.hasIdr(ByteArray(0)))
    }

    @Test
    fun `a buffer with an SPS or a PPS carries parameter sets`() {
        assertTrue(AnnexB.hasParameterSets(four + sps + four + pps))
        assertTrue(AnnexB.hasParameterSets(four + pps))
        assertFalse(AnnexB.hasParameterSets(four + idr))
    }
}
