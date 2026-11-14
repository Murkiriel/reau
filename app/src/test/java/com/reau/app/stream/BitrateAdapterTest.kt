package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The experimental live adjustment of the bitrate: measured over 3 s, lowered to `current * target / real` above 125 % of the target (never under
 * 64 kbps), raised 15 % under 70 % of it while below the target. Bytes are given as what the encoder put out in a window of 3 s: bits / 8 * 3.
 */
class BitrateAdapterTest {
    private val target = 200_000
    private val adapter = BitrateAdapter().also { it.setTarget(target) }

    /** A window of 3 s with [bps] coming out, closed at [endMs]; the first call of a stream only opens the window. */
    private fun window(bps: Long, endMs: Long) = adapter.onOutput(endMs, bps * 3 / 8)

    private fun open() = assertNull(adapter.onOutput(0, 0))

    @Test
    fun `an encoder that puts out the target is left alone`() {
        open()
        assertNull(window(200_000, 3_000))
        assertNull(window(240_000, 6_000)) // 120 %
        assertEquals(200_000, adapter.current)
    }

    @Test
    fun `above 125 percent the bitrate asked comes down to current times target over real`() {
        open()
        // 300 kbps real for a target of 200: 200 * 200 / 300 = 133.3 kbps.
        assertEquals(133_333, window(300_000, 3_000))
        assertEquals(133_333, adapter.current)
    }

    @Test
    fun `exactly 125 percent is not over`() {
        open()
        assertNull(window(250_000, 3_000))
    }

    @Test
    fun `just over 125 percent is`() {
        open()
        assertEquals(true, window(251_000, 3_000) != null)
    }

    @Test
    fun `it never goes under 64 kbps`() {
        open()
        assertEquals(64_000, window(2_000_000, 3_000)) // 200 * 200 / 2000 = 20 kbps, held at the floor
    }

    @Test
    fun `under 70 percent with the bitrate asked below the target it goes up 15 percent`() {
        open()
        window(300_000, 3_000) // down to 133_333
        assertEquals(153_332, window(50_000, 6_000)) // 133_333 * 1.15, rounded down
    }

    @Test
    fun `under 70 percent with the bitrate at the target nothing is raised`() {
        open()
        assertNull(window(50_000, 3_000))
        assertEquals(200_000, adapter.current)
    }

    @Test
    fun `exactly 70 percent is not under`() {
        open()
        window(300_000, 3_000)
        assertNull(window(140_000, 6_000))
    }

    @Test
    fun `the raise stops at the target`() {
        open()
        window(300_000, 3_000) // 133_333
        var last = 133_333
        for (n in 2..8) window(10_000, n * 3_000L)?.let { last = it }
        assertEquals(200_000, last)
        assertEquals(200_000, adapter.current)
    }

    @Test
    fun `nothing is decided before the window is 3 s long`() {
        open()
        assertNull(adapter.onOutput(1_000, 900_000))
        assertNull(adapter.onOutput(2_900, 900_000))
        assertEquals(true, adapter.onOutput(3_000, 0) != null)
    }

    @Test
    fun `a new target starts the adjustment over from it`() {
        open()
        window(300_000, 3_000)
        adapter.setTarget(100_000)
        assertEquals(100_000, adapter.current)
        open()
        assertEquals(64_000, window(1_000_000, 3_000).let { it })
    }

    @Test
    fun `with no target nothing is adjusted`() {
        val none = BitrateAdapter()
        assertNull(none.onOutput(0, 0))
        assertNull(none.onOutput(10_000, 10_000_000))
    }

    @Test
    fun `the switch off gives no adjustment, on gives one`() {
        assertNull(BitrateAdapter.whenOn(false))
        assertEquals(true, BitrateAdapter.whenOn(true) != null)
    }

    @Test
    fun `the numbers are 3 s, 125 percent, 70 percent, 15 percent and a floor of 64 kbps`() {
        assertEquals(listOf(3_000L, 125L, 70L, 15L), listOf(BitrateAdapter.WINDOW_MS, BitrateAdapter.HIGH_PERCENT, BitrateAdapter.LOW_PERCENT, BitrateAdapter.RAISE_PERCENT))
        assertEquals(64_000, com.reau.app.dash.DashConfig.EXPERIMENT_FLOOR_BPS)
        assertEquals(200_000, com.reau.app.dash.DashConfig.EXPERIMENT_TARGET_BPS)
    }
}
