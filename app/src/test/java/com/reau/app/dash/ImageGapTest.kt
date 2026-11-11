package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line written when the image is back after a drop (item 15a (v), 08/11/2026): the time without it in all and with the dash on. */
class ImageGapTest {
    private fun streamed() = ImageGapClock().also { assertNull(it.streaming(1_000L)) }

    @Test
    fun `the first stream of a projection says nothing`() {
        assertNull(ImageGapClock().streaming(5_000L))
    }

    @Test
    fun `a stream that comes back after the dash was switched off gives the time in all and the time with the dash on`() {
        // 06/11 17:44:06: lost; the dash came on at 17:46:26.7, its heartbeat age 85 s at the first packet 17:47:51.7; STREAMING 17:47:53.4.
        val gap = streamed()
        val lostAt = 100_000L
        gap.lost(nowMs = lostAt + 100, heardAgoMs = 100) // last packet 0.1 s before
        gap.dashAge(nowMs = lostAt + 221_900, ageSeconds = 85)
        assertEquals(
            "image back: 223.4 s without it since the last dash packet before the loss; with the dash on: " +
                "86.5 s (it came on 136.9 s into the loss)",
            gap.streaming(lostAt + 223_400),
        )
    }

    @Test
    fun `a drop with the dash on all along says it stayed on, and the two numbers are the same`() {
        val gap = streamed()
        gap.lost(nowMs = 50_000, heardAgoMs = 0)
        gap.dashAge(nowMs = 77_000, ageSeconds = 90) // came on before the loss
        assertEquals(
            "image back: 30.0 s without it since the last dash packet before the loss; with the dash on: 30.0 s (it stayed on)",
            gap.streaming(80_000),
        )
    }

    @Test
    fun `a gap whose dash age was never read says unknown for the dash on`() {
        val gap = streamed()
        gap.lost(nowMs = 50_000, heardAgoMs = 4_000)
        assertEquals(
            "image back: 34.0 s without it since the last dash packet before the loss; with the dash on: unknown",
            gap.streaming(80_000),
        )
    }

    @Test
    fun `over 256 s the counter has wrapped and the dash's time is at least`() {
        val gap = streamed()
        gap.lost(nowMs = 10_000, heardAgoMs = 0)
        gap.dashAge(nowMs = 380_000, ageSeconds = 19)
        assertEquals(
            "image back: 381.0 s without it since the last dash packet before the loss; with the dash on: " +
                "at least 30.0 s (it came on 351.0 s into the loss)",
            gap.streaming(391_000),
        )
    }

    @Test
    fun `the first loss of a gap counts, a second one while it lasts does not, and the last dash age wins`() {
        val gap = streamed()
        gap.lost(nowMs = 10_000, heardAgoMs = 0)
        gap.lost(nowMs = 14_000, heardAgoMs = 0) // the watchdog, 4 s later
        gap.dashAge(nowMs = 40_000, ageSeconds = 5)
        gap.dashAge(nowMs = 50_000, ageSeconds = 15) // the same dash, a second session: 35 000
        assertEquals(
            "image back: 50.0 s without it since the last dash packet before the loss; with the dash on: 25.0 s (it came on 25.0 s into the loss)",
            gap.streaming(60_000),
        )
    }

    @Test
    fun `a stream that was never interrupted says nothing the second time, and a loss before any stream counts for nothing`() {
        val early = ImageGapClock()
        early.lost(nowMs = 5_000, heardAgoMs = 0) // before the first stream
        assertNull(early.streaming(9_000))
        assertNull(early.streaming(12_000))
    }

    @Test
    fun `the gap closes with its line, and a new projection forgets one that was open`() {
        val gap = streamed()
        gap.lost(nowMs = 10_000, heardAgoMs = 0)
        gap.forget()
        assertNull("forgotten: the first stream of the new projection", gap.streaming(20_000))
        gap.lost(nowMs = 30_000, heardAgoMs = 0)
        gap.streaming(40_000)
        assertNull("closed", gap.streaming(50_000))
    }

    // ── the total of the gaps, for the projection's resumo (`no image=`) ──

    @Test
    fun `the gaps that ended are summed, and the one going on counts up to now`() {
        val gap = streamed()
        assertEquals("no loss yet: nothing", 0L, gap.totalMs(2_000))
        gap.lost(nowMs = 10_000, heardAgoMs = 400) // counts from the last packet: 9 600
        assertEquals("a gap going on counts up to now", 5_400L, gap.totalMs(15_000))
        gap.streaming(20_000)
        assertEquals("ended: 20 000 - 9 600", 10_400L, gap.totalMs(30_000))
        gap.lost(nowMs = 40_000, heardAgoMs = 0)
        gap.streaming(46_500)
        assertEquals("two gaps: 10 400 + 6 500", 16_900L, gap.totalMs(60_000))
        gap.lost(nowMs = 70_000, heardAgoMs = 0)
        assertEquals("the third still on, at 75 000", 16_900L + 5_000, gap.totalMs(75_000))
    }

    @Test
    fun `a new projection starts the total over`() {
        val gap = streamed()
        gap.lost(nowMs = 10_000, heardAgoMs = 0)
        gap.streaming(20_000)
        gap.forget()
        assertEquals(0L, gap.totalMs(30_000))
    }

    @Test
    fun `a loss before the first stream of a projection is no gap`() {
        val gap = ImageGapClock()
        gap.lost(nowMs = 1_000, heardAgoMs = 0)
        assertEquals(0L, gap.totalMs(9_000))
    }
}
