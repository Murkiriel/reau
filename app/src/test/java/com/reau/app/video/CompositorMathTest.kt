package com.reau.app.video

import com.reau.app.stream.DashLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compositor's arithmetic, away from OpenGL: what to do at a tick of its clock, which part of the source
 * texture to sample, where on the encoder's frame to draw, and the time each frame is stamped with.
 */
class CompositorMathTest {
    @Test
    fun `the phone preview shows Android Auto's whole picture as big as fits, centred`() {
        // A screen in the app to see Android Auto running in the background.
        assertEquals(RectPx(0, 636, 1080, 648), CompositorMath.previewViewport(800, 480, 1080, 1920))   // portrait phone
        assertEquals(RectPx(300, 0, 1800, 1080), CompositorMath.previewViewport(800, 480, 2400, 1080))  // landscape
        assertEquals(RectPx(0, 0, 800, 480), CompositorMath.previewViewport(800, 480, 800, 480))
        assertEquals(RectPx(0, 0, 0, 0), CompositorMath.previewViewport(800, 480, 0, 0))                // no surface yet
    }


    // ── draw now, repeat, or wait ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a new image from Android Auto is drawn at the next tick`() {
        assertEquals(TickAction.DRAW, CompositorMath.decide(newImage = true, sinceLastFrameMs = 62, activeMs = 62, idleMs = 250))
        assertEquals(TickAction.DRAW, CompositorMath.decide(newImage = true, sinceLastFrameMs = 250, activeMs = 62, idleMs = 250))
    }

    @Test
    fun `with nothing new the last image is repeated at the tick nearest to the idle interval`() {
        // 16 frames/s: ticks every 62 ms. 248 ms is the tick nearest to 250, so that is where the repeat goes.
        assertEquals(TickAction.WAIT, CompositorMath.decide(newImage = false, sinceLastFrameMs = 62, activeMs = 62, idleMs = 250))
        assertEquals(TickAction.WAIT, CompositorMath.decide(newImage = false, sinceLastFrameMs = 186, activeMs = 62, idleMs = 250))
        assertEquals(TickAction.REPEAT, CompositorMath.decide(newImage = false, sinceLastFrameMs = 248, activeMs = 62, idleMs = 250))
        // 30 frames/s: ticks every 33 ms. 231 is 19 ms short of 250, 264 is 14 ms past it.
        assertEquals(TickAction.WAIT, CompositorMath.decide(newImage = false, sinceLastFrameMs = 231, activeMs = 33, idleMs = 250))
        assertEquals(TickAction.REPEAT, CompositorMath.decide(newImage = false, sinceLastFrameMs = 264, activeMs = 33, idleMs = 250))
    }

    @Test
    fun `at the still pace every tick repeats`() {
        assertEquals(TickAction.REPEAT, CompositorMath.decide(newImage = false, sinceLastFrameMs = 250, activeMs = 250, idleMs = 250))
    }

    @Test
    fun `a source that stays quiet gets the idle rate, not the active one`() {
        for ((activeMs, idleMs) in listOf(62L to 250L, 33L to 250L, 125L to 250L, 250L to 250L, 71L to 250L)) {
            var since = 0L
            var frames = 0
            var elapsed = 0L
            while (elapsed < 60_000) {
                elapsed += activeMs
                since += activeMs
                if (CompositorMath.decide(newImage = false, sinceLastFrameMs = since, activeMs = activeMs, idleMs = idleMs) == TickAction.REPEAT) {
                    frames++
                    since = 0
                }
            }
            val averageMs = 60_000.0 / frames
            assertTrue("active=$activeMs: a repeat every $averageMs ms", averageMs >= idleMs - activeMs / 2.0 && averageMs <= idleMs + activeMs / 2.0)
        }
    }

    // ── which part of the source texture ───────────────────────────────────────────────────────────────────

    @Test
    fun `the whole frame is the whole texture`() {
        assertWindow(0f, 0f, 1f, 1f, CompositorMath.texWindow(RectPx(0, 0, 800, 480), 800, 480))
    }

    @Test
    fun `a centred window is sampled from the middle`() {
        // 500×310 at (150, 85) of 800×480.
        assertWindow(150f / 800, 85f / 480, 500f / 800, 310f / 480, CompositorMath.texWindow(RectPx(150, 85, 500, 310), 800, 480))
    }

    @Test
    fun `texture rows are counted from the bottom, so a window at the top starts high`() {
        // The window's rows 0..310 are the TOP of the picture; in texture space that is v from 170/480 up to 1.
        assertWindow(0f, 170f / 480, 500f / 800, 310f / 480, CompositorMath.texWindow(RectPx(0, 0, 500, 310), 800, 480))
        // And one at the bottom right starts at v = 0.
        assertWindow(300f / 800, 0f, 500f / 800, 310f / 480, CompositorMath.texWindow(RectPx(300, 170, 500, 310), 800, 480))
    }

    @Test
    fun `the content area left by margins is a window like any other`() {
        assertWindow(13f / 800, 0f, 774f / 800, 1f, CompositorMath.texWindow(AaMargins(26, 0).contentRect(800, 480), 800, 480))
    }

    // ── where on the encoder's frame ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the viewport is the destination with its rows counted from the bottom of the 300-row frame`() {
        assertEquals(RectPx(113, 114, 300, 180), CompositorMath.viewport(RectPx(113, 6, 300, 180)))
        assertEquals(RectPx(151, 114, 224, 134), CompositorMath.viewport(RectPx(151, 52, 224, 134)))
        assertEquals(RectPx(0, 0, 526, 300), CompositorMath.viewport(DashGlass.DOME_BOX))
        assertEquals(RectPx(13, 0, 500, 300), CompositorMath.viewport(RectPx(13, 0, 500, 300)))
        assertEquals(RectPx(0, 0, 526, 300), CompositorMath.viewport(RectPx(0, 0, 526, 300)))
        assertEquals(RectPx(10, 250, 20, 30), CompositorMath.viewport(RectPx(10, 20, 20, 30)))
    }

    // ── the time on each frame ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the stamp is the video clock in whole milliseconds`() {
        assertEquals(62L, CompositorMath.stamp(previousStampMs = 0, ptsMs = 62.0))
        assertEquals(58L, CompositorMath.stamp(previousStampMs = 0, ptsMs = 58.9))
        assertEquals(0L, CompositorMath.stamp(previousStampMs = -1, ptsMs = 0.0))
    }

    @Test
    fun `a stamp is always later than the one before`() {
        assertEquals(63L, CompositorMath.stamp(previousStampMs = 62, ptsMs = 62.4))
        assertEquals(101L, CompositorMath.stamp(previousStampMs = 100, ptsMs = 40.0))
    }

    @Test
    fun `stamps follow the video clock at 0 and at 5 percent slow`() {
        fun stamps(slowPercent: Int): List<Long> {
            var pts = 0.0
            var stamp = 0L
            return List(4) {
                pts = DashLogic.nextVideoPts(pts, 62, slowPercent)
                stamp = CompositorMath.stamp(stamp, pts)
                stamp
            }
        }
        assertEquals(listOf(62L, 124L, 186L, 248L), stamps(0))
        assertEquals(listOf(58L, 117L, 176L, 235L), stamps(5)) // 58.9, 117.8, 176.7, 235.6
    }

    private fun assertWindow(offsetU: Float, offsetV: Float, scaleU: Float, scaleV: Float, actual: CompositorMath.TexWindow) {
        assertEquals("offsetU", offsetU, actual.offsetU, 1e-6f)
        assertEquals("offsetV", offsetV, actual.offsetV, 1e-6f)
        assertEquals("scaleU", scaleU, actual.scaleU, 1e-6f)
        assertEquals("scaleV", scaleV, actual.scaleV, 1e-6f)
    }
}
