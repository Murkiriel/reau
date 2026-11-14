package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compositor's clock and frame rules, with no OpenGL: what a pace may be, where the next tick falls, the time of each frame. */
class CompositorRulesTest {
    @Test
    fun `a pace is a tick of at least a millisecond and a repeat never sooner than the tick`() {
        assertEquals(DashCompositor.Pace(250, 250, 5), CompositorRules.normalized(DashCompositor.Pace(250, 250, 5)))
        assertEquals("a tick of nothing", DashCompositor.Pace(1, 500, 0), CompositorRules.normalized(DashCompositor.Pace(0, 500)))
        assertEquals("a negative one", 1L, CompositorRules.normalized(DashCompositor.Pace(-5, 100)).activeMs)
        assertEquals("a repeat sooner than the tick", DashCompositor.Pace(250, 250, 0), CompositorRules.normalized(DashCompositor.Pace(250, 100)))
        assertEquals("a repeat later is kept", DashCompositor.Pace(62, 500, 3), CompositorRules.normalized(DashCompositor.Pace(62, 500, 3)))
    }

    @Test
    fun `the compositor is busy for as long as the frame it is in has run, and not at all between frames`() {
        assertEquals("between frames", 0L, CompositorRules.busyMs(startedAtMs = 0, nowMs = 123_456))
        assertEquals(0L, CompositorRules.busyMs(startedAtMs = 5_000, nowMs = 5_000))
        assertEquals(1_250L, CompositorRules.busyMs(startedAtMs = 5_000, nowMs = 6_250))
        assertEquals("a clock that went back is not a negative wait", 0L, CompositorRules.busyMs(startedAtMs = 5_000, nowMs = 4_000))
    }

    @Test
    fun `the first tick is anchored on now and the next ones keep the grid`() {
        assertEquals("never anchored", 1_250L, CompositorRules.nextTickAt(nextTickAtMs = 0, tickSpanMs = 250, nowMs = 1_000))
        assertEquals("never anchored, on a clock that has just begun", 350L, CompositorRules.nextTickAt(0, 250, 100))
        assertEquals("on time: one step on the grid, not from now", 1_500L, CompositorRules.nextTickAt(1_250, 250, 1_010))
        assertEquals("a little late is still on the grid", 1_500L, CompositorRules.nextTickAt(1_250, 250, 1_240))
    }

    @Test
    fun `a tick that fell a whole interval behind is anchored on now, with no burst to catch up`() {
        assertEquals("exactly one interval behind", 1_750L, CompositorRules.nextTickAt(1_250, 250, 1_500))
        assertEquals("far behind", 6_250L, CompositorRules.nextTickAt(1_250, 250, 6_000))
        assertEquals("one millisecond short of a whole interval", 1_500L, CompositorRules.nextTickAt(1_250, 250, 1_499))
    }

    @Test
    fun `the first frame does not move the clock and the next ones move it by the time since the last`() {
        val first = CompositorRules.advance(lastStampMs = -1, ptsMs = 0.0, sinceLastFrameMs = 250, clockSlowPercent = 0)
        assertEquals(0.0, first.ptsMs, 0.0)
        assertEquals(0L, first.stampMs)
        val second = CompositorRules.advance(lastStampMs = 0, ptsMs = 0.0, sinceLastFrameMs = 250, clockSlowPercent = 0)
        assertEquals(250.0, second.ptsMs, 0.0)
        assertEquals(250L, second.stampMs)
    }

    @Test
    fun `the clock knob slows the clock and the fraction of a millisecond is kept`() {
        val slow = CompositorRules.advance(lastStampMs = 1_000, ptsMs = 1_000.0, sinceLastFrameMs = 250, clockSlowPercent = 5)
        assertEquals(1_237.5, slow.ptsMs, 0.0001)
        assertEquals(1_237L, slow.stampMs)
    }

    @Test
    fun `a stamp is always after the last one and the clock never stays behind it`() {
        // two frames in the same millisecond of the clock: the second is stamped one later, and the clock catches up
        val clock = CompositorRules.advance(lastStampMs = 1_000, ptsMs = 1_000.2, sinceLastFrameMs = 0, clockSlowPercent = 0)
        assertEquals(1_001L, clock.stampMs)
        assertEquals(1_001.0, clock.ptsMs, 0.0)
        val ahead = CompositorRules.advance(lastStampMs = 1_000, ptsMs = 1_500.0, sinceLastFrameMs = 0, clockSlowPercent = 0)
        assertEquals(1_500L, ahead.stampMs)
        assertEquals("a clock already ahead stays where it is", 1_500.0, ahead.ptsMs, 0.0)
    }

    @Test
    fun `a refused frame is told once in a run of refusals`() {
        assertTrue(CompositorRules.refusalToTell(posted = false, alreadyFailing = false))
        assertFalse("already told", CompositorRules.refusalToTell(posted = false, alreadyFailing = true))
        assertFalse(CompositorRules.refusalToTell(posted = true, alreadyFailing = false))
        assertFalse(CompositorRules.refusalToTell(posted = true, alreadyFailing = true))
    }

    @Test
    fun `the web picture is drawn again every interval, and never without a picture`() {
        val every = 100_000_000L
        assertTrue(CompositorRules.webDue(hasImage = true, nowNs = 1_000_000_000, lastDrawNs = 900_000_000, everyNs = every))
        assertFalse(CompositorRules.webDue(hasImage = true, nowNs = 999_999_999, lastDrawNs = 900_000_000, everyNs = every))
        assertTrue(CompositorRules.webDue(hasImage = true, nowNs = 2_000_000_000, lastDrawNs = 900_000_000, everyNs = every))
        assertFalse("no picture to draw", CompositorRules.webDue(hasImage = false, nowNs = 2_000_000_000, lastDrawNs = 900_000_000, everyNs = every))
    }

    @Test
    fun `the look at the picture is taken at most once in its interval`() {
        assertTrue(CompositorRules.lookDue(nowMs = 1_100, lastLookMs = 1_000, everyMs = 100))
        assertFalse(CompositorRules.lookDue(nowMs = 1_099, lastLookMs = 1_000, everyMs = 100))
        assertTrue(CompositorRules.lookDue(nowMs = 5_000, lastLookMs = 1_000, everyMs = 100))
        assertTrue("the first look", CompositorRules.lookDue(nowMs = 50, lastLookMs = 0, everyMs = 50))
    }
}
