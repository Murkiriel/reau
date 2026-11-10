package com.reau.app.dash.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** The time each frame spent in the encoder, kept frame by frame for the worst and the 95th percentile of the `video fps` window. */
class PendingFramesStageTest {
    private val ms = 1_000_000L

    @Test
    fun `each frame's time in the encoder is kept, not only the sum`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 0, nowNs = 0)
        p.emitted(14 * ms)
        p.submitted(framePtsMs = 250, nowNs = 250 * ms)
        p.emitted(250 * ms + 19_200_000L)
        val taken = p.takeStats()
        assertEquals(2, taken.latency.count)
        assertEquals(19.2, taken.latency.maxMs, 1e-9)
        assertEquals(taken.latencySumNs.toDouble() / ms / 2, taken.latency.meanMs, 1e-9)
    }

    @Test
    fun `a frame the encoder gave no timing for adds nothing, and the next take starts again`() {
        val p = PendingFrames()
        p.emitted(5 * ms) // nothing was submitted: no entry, no time
        assertEquals(0, p.takeStats().latency.count)
        p.submitted(framePtsMs = 0, nowNs = 0)
        p.emitted(8 * ms)
        assertEquals(1, p.takeStats().latency.count)
        assertEquals(0, p.takeStats().latency.count)
    }
}
