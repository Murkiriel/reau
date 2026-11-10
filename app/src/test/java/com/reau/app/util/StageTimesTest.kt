package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The worst frame and the 95th percentile of a window, kept without allocating per sample, and the fields they make in the `video fps` line. */
class StageTimesTest {
    private val ms = 1_000_000L

    private fun statsOf(vararg ns: Long): StageStats = StageClock().also { c -> ns.forEach(c::add) }.take()

    @Test
    fun `count, mean and worst are exact`() {
        val s = statsOf(2 * ms, 4 * ms, 12 * ms)
        assertEquals(3, s.count)
        assertEquals(6.0, s.meanMs, 1e-9)
        assertEquals(12.0, s.maxMs, 1e-9)
    }

    @Test
    fun `the 95th percentile is the sample the rank lands on, to within half a millisecond, and never over the worst`() {
        // 100 frames of 3.0 ms and 5 of 20 ms: the 95th of 105 is the 100th, one of the 3 ms ones.
        val s = statsOf(*(List(100) { 3 * ms } + List(5) { 20 * ms }).toLongArray())
        assertEquals(3.5, s.p95Ms, 1e-9)
        assertEquals(20.0, s.maxMs, 1e-9)
        // 19 frames of 3 ms and one of 20 ms: the 95th of 20 is the 19th, still a 3 ms one; the worst is not the 95th.
        assertEquals(3.5, statsOf(*(List(19) { 3 * ms } + 20 * ms).toLongArray()).p95Ms, 1e-9)
        // A single sample: the percentile is clamped to the sample itself, not the top edge of its bucket.
        assertEquals(3.2, statsOf(3_200_000L).p95Ms, 1e-9)
    }

    @Test
    fun `a window where the tail is long has a p95 in the tail`() {
        // 10 frames of 3 ms, 10 of 18 ms: the 95th of 20 is the 19th, an 18 ms one.
        val s = statsOf(*(List(10) { 3 * ms } + List(10) { 18 * ms }).toLongArray())
        assertEquals(18.0, s.p95Ms, 1e-9)
    }

    @Test
    fun `a sample past the histogram reads as the worst one`() {
        val s = statsOf(3 * ms, 500 * ms)
        assertEquals(500.0, s.maxMs, 1e-9)
        assertEquals(500.0, s.p95Ms, 1e-9)
    }

    @Test
    fun `a negative sample counts as zero and take starts again`() {
        val clock = StageClock()
        clock.add(-5 * ms)
        clock.add(7 * ms)
        val first = clock.take()
        assertEquals(2, first.count)
        assertEquals(7.0, first.maxMs, 1e-9)
        assertEquals(3.5, first.meanMs, 1e-9)
        assertSame("nothing since the last take", StageStats.EMPTY, clock.take())
        clock.add(2 * ms)
        val second = clock.take()
        assertEquals(1, second.count)
        assertEquals("the worst starts again", 2.0, second.maxMs, 1e-9)
        assertEquals("the sum starts again", 2.0, second.meanMs, 1e-9)
        assertEquals("the histogram starts again", 2.0, second.p95Ms, 1e-9)
    }

    @Test
    fun `the totals of several polls give the window's worst and percentile`() {
        val totals = StageTotals()
        totals.add(statsOf(3 * ms, 3 * ms, 9 * ms))
        totals.add(StageStats.EMPTY)
        totals.add(statsOf(*LongArray(18) { 3 * ms }))
        val s = totals.stats()
        assertEquals(21, s.count)
        assertEquals("the worst is the first poll's, not the last", 9.0, s.maxMs, 1e-9)
        assertEquals(3.5, s.p95Ms, 1e-9)
        assertEquals((3 * 20 + 9) / 21.0, s.meanMs, 1e-9)
        assertSame(StageStats.EMPTY, StageTotals().stats())
    }

    @Test
    fun `the line's fields come after the original ones, in a format the reader knows`() {
        val text = StageReport.text(
            compose = statsOf(*(List(19) { 3_400_000L } + 7_600_000L).toLongArray()),
            encode = statsOf(14 * ms, 19_200_000L),
            look = statsOf(900_000L, 1_400_000L, 400_000L),
            latch = statsOf(*List(150) { 200_000L }.toLongArray(), 900_000L),
            wakeups = 152, elapsedMs = 5_000,
        )
        assertEquals(
            " compose_max=7.6ms compose_p95=3.5ms encode_max=19.2ms encode_p95=19.2ms look=3/0.90/1.40ms latch=151/0.20/0.90ms wake=152(30.4/s)",
            text,
        )
    }

    @Test
    fun `a window with nothing measured says so with dashes and zeros`() {
        val none = StageStats.EMPTY
        assertEquals(" compose_max=- compose_p95=- encode_max=- encode_p95=- look=0 latch=0 wake=0(0.0/s)", StageReport.text(none, none, none, none, 0, 5_000))
        assertTrue(StageReport.text(none, none, none, none, 10, 0).endsWith("wake=10(0.0/s)"))
    }
}
