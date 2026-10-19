package com.reau.app.dash.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingFramesTest {
    private val ms = 1_000_000L

    @Test
    fun `each frame comes out with the time it went in with`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 62, nowNs = 0)
        assertEquals(PendingFrames.Emitted(62, late = false), p.emitted(17 * ms))
        p.submitted(framePtsMs = 124, nowNs = 62 * ms)
        assertEquals(PendingFrames.Emitted(124, late = false), p.emitted(80 * ms))
        assertEquals(PendingFrames.Stats(frames = 2, late = 0, latencySumNs = 35 * ms), p.takeStats())
    }

    /** The case this was written for: the encoder had not finished frame 1 when frame 2 went in. Both used
     *  to leave with frame 2's time. */
    @Test
    fun `a frame that comes out after the next one went in keeps its own time and counts as late`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 33, nowNs = 0)
        p.submitted(framePtsMs = 66, nowNs = 33 * ms)
        assertEquals(PendingFrames.Emitted(33, late = true), p.emitted(40 * ms))
        assertEquals(PendingFrames.Emitted(66, late = false), p.emitted(52 * ms))
        assertEquals(PendingFrames.Stats(frames = 2, late = 1, latencySumNs = 59 * ms), p.takeStats())
    }

    @Test
    fun `a frame the encoder swallowed does not shift the ones after it`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 100, nowNs = 0)                      // never comes out
        p.submitted(framePtsMs = 200, nowNs = 1_100 * ms)
        assertEquals(PendingFrames.Emitted(200, late = false), p.emitted(1_117 * ms))
        p.submitted(framePtsMs = 300, nowNs = 1_200 * ms)
        assertEquals(PendingFrames.Emitted(300, late = false), p.emitted(1_217 * ms))
    }

    @Test
    fun `a single old frame is still matched, however long the encoder took`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 250, nowNs = 0)
        assertEquals(PendingFrames.Emitted(250, late = false), p.emitted(3_000 * ms))
    }

    @Test
    fun `an output with nothing pending repeats the last time and is not counted`() {
        val p = PendingFrames()
        assertEquals(PendingFrames.Emitted(0, late = false), p.emitted(5 * ms))
        p.submitted(framePtsMs = 40, nowNs = 10 * ms)
        p.emitted(20 * ms)
        assertEquals(PendingFrames.Emitted(40, late = false), p.emitted(30 * ms))
        assertEquals(40L, p.lastPtsMs())
        assertEquals(PendingFrames.Stats(frames = 1, late = 0, latencySumNs = 10 * ms), p.takeStats())
    }

    @Test
    fun `taking the stats starts a new count`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 1, nowNs = 0); p.emitted(4 * ms)
        p.takeStats()
        assertEquals(PendingFrames.Stats(frames = 0, late = 0, latencySumNs = 0), p.takeStats())
    }
}
