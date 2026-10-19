package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto's side counted before the decoder, one
 * `aa-stats` line per 5 s window with the session's totals behind it.
 */
class AaStatsTest {
    private var nowMs = 100_000L
    private val stats = AaStats { nowMs }

    /** The "app" numbers of the stats text count each picture once (it counted twice). */
    @Test
    fun `the process-wide meter counts each unit and its bytes once`() {
        val units = AaVideoMeter.units.get()
        val bytes = AaVideoMeter.bytes.get()
        stats.unit(bytes = 1_000, keyframe = true)
        stats.unit(bytes = 500, keyframe = false)
        assertEquals(2L, AaVideoMeter.units.get() - units)
        assertEquals(1_500L, AaVideoMeter.bytes.get() - bytes)
        assertEquals(2L, stats.totals().units)
    }

    @Test
    fun `the window line says every count in a fixed order, with the totals`() {
        repeat(3) { stats.message(Channel.ID_VID) }
        stats.message(Channel.ID_CTR)
        stats.message(Channel.ID_NAV)
        stats.unit(bytes = 10_240, keyframe = true)
        stats.unit(bytes = 2_048, keyframe = false)
        repeat(3) { stats.piece() }
        stats.assembled()
        stats.lost()
        stats.focus()
        stats.sensor()
        stats.decoded(latencyUs = 10_000)
        stats.decoded(latencyUs = 30_000)
        nowMs += 5_000
        assertEquals(
            "aa-stats 5.0s: msgs=5 (ch0=1 ch2=3 ch10=1) nav=1 units=2 bytes=12KB key=1 pieces=3 assembled=1 lost=1 " +
                "focus=1 sensor=1 decoded=2 decode=20/30ms readHeld=0.0/0.0ms | total units=2 key=1 assembled=1 lost=1 decoded=2",
            stats.windowLine(),
        )
    }

    @Test
    fun `a new window starts empty while the totals go on`() {
        stats.unit(bytes = 1_024, keyframe = false)
        stats.lost()
        nowMs += 5_000
        stats.windowLine()
        stats.unit(bytes = 1_024, keyframe = true)
        nowMs += 2_500
        assertEquals(
            "aa-stats 2.5s: msgs=0 () nav=0 units=1 bytes=1KB key=1 pieces=0 assembled=0 lost=0 " +
                "focus=0 sensor=0 decoded=0 decode=- readHeld=0.0/0.0ms | total units=2 key=1 assembled=0 lost=1 decoded=0",
            stats.windowLine(),
        )
        assertEquals(AaStats.Totals(units = 2, keyframes = 1, assembled = 0, lost = 1, decoded = 0, messages = 0), stats.totals())
    }

    @Test
    fun `the totals count messages by channel too`() {
        repeat(4) { stats.message(Channel.ID_VID) }
        stats.message(Channel.ID_SEN)
        assertEquals(mapOf(Channel.ID_SEN to 1L, Channel.ID_VID to 4L), stats.messagesByChannel())
        assertEquals(5L, stats.totals().messages)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `an access unit with an IDR slice is a keyframe, found behind the SPS and PPS`() {
        val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0, 0x29, 0x8D)
        val pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x01)
        val idr = bytes(0, 0, 1, 0x65, 0x88, 0x84)
        val p = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x02)
        val all = sps + pps + idr
        assertTrue(AaStats.hasIdr(all, 0, all.size))
        assertTrue("from an offset", AaStats.hasIdr(bytes(9, 9) + idr, 2, idr.size))
        assertFalse(AaStats.hasIdr(p, 0, p.size))
        assertFalse("the configuration alone", AaStats.hasIdr(sps + pps, 0, sps.size + pps.size))
    }
}
