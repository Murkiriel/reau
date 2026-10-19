package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the dash's glass, frame and ring fall on the phone's "Tela do Android Auto", for the fit of the settings. The
 * numbers are worked out by hand in each test.
 */
class DashMaskTest {
    private val whole800 = RectPx(0, 0, 800, 480)

    private fun near(expected: Float, actual: Float, what: String) = assertEquals(what, expected, actual, 0.05f)

    @Test
    fun `whole and visible on a screen of the frame's own size`() {
        // dest (78, 78, 370, 222), source the whole 800×480: 800 / 370 = 2.16216 Android Auto pixels per dash pixel.
        val m = checkNotNull(DashMask.of(FrameFit.wholeVisible(whole800), 800, 480, 800, 480))
        near(400f, m.glass.cx, "glass x: (263 - 78) × 2.16216")
        near(400f, m.glass.cy, "glass y: (263 - 78) × 2.16216")
        near(568.65f, m.glass.r, "glass radius: 263 × 2.16216")
        near(15.14f, m.bubble.cx, "ring x: (85 - 78) × 2.16216")
        near(335.14f, m.bubble.cy, "ring y: (233 - 78) × 2.16216")
        near(129.73f, m.bubble.r, "ring radius: 60 × 2.16216")
        near(480f, m.frame.bottom, "the frame's bottom: (300 - 78) × 2.16216")
        near(-168.65f, m.frame.left, "the frame's left: -78 × 2.16216")
    }

    @Test
    fun `whole and visible shows every corner of the picture`() {
        val m = checkNotNull(DashMask.of(FrameFit.wholeVisible(whole800), 800, 480, 800, 480))
        for ((x, y) in listOf(1f to 1f, 799f to 1f, 1f to 479f, 799f to 479f, 400f to 240f)) {
            assertTrue("($x, $y) reaches the glass", m.visible(x, y))
        }
        assertTrue("the ring covers the bottom left", m.underBubble(15f, 335f))
        assertFalse("not the middle", m.underBubble(400f, 240f))
    }

    @Test
    fun `fill the dome cuts the picture's top corners and keeps its bottom`() {
        // dest (13, 0, 500, 300): 800 / 500 = 1.6; the glass at ((263 - 13) × 1.6, 263 × 1.6) = (400, 420.8), radius
        // 420.8.
        val m = checkNotNull(DashMask.of(FrameFit.fillDome(whole800), 800, 480, 800, 480))
        near(400f, m.glass.cx, "glass x")
        near(420.8f, m.glass.cy, "glass y")
        near(420.8f, m.glass.r, "glass radius")
        assertFalse("top left corner cut (580 from the centre)", m.visible(1f, 1f))
        assertFalse("top right corner cut", m.visible(799f, 1f))
        assertTrue("bottom left shows (404 from the centre)", m.visible(1f, 479f))
        assertTrue("the middle shows", m.visible(400f, 240f))
    }

    @Test
    fun `the preview's black bands are never shown`() {
        // An 800×480 picture on a 1000×1000 screen: 1000 wide, 600 tall, from y 200 (previewViewport); 1.25 screen
        // pixels per Android Auto pixel, so the glass of the whole-visible fit is at (500, 200 + 400 × 1.25).
        val m = checkNotNull(DashMask.of(FrameFit.wholeVisible(whole800), 800, 480, 1000, 1000))
        near(500f, m.glass.cx, "glass x")
        near(700f, m.glass.cy, "glass y")
        near(710.81f, m.glass.r, "glass radius: 568.65 × 1.25")
        assertFalse("above the picture", m.visible(500f, 150f))
        assertTrue("in the picture", m.visible(500f, 500f))
    }

    @Test
    fun `a picture moved right follows the settings and loses its top right corner`() {
        // Whole visible moved 100 dash pixels right stops at the frame's edge (FrameFit.moved): dest (156, 78, 370,
        // 222). The glass is now at ((263 - 156) × 2.16216, 400) = (231.35, 400); the top right corner is 694 from it.
        val m = checkNotNull(DashMask.of(FrameFit.moved(FrameFit.wholeVisible(whole800), 100, 0), 800, 480, 800, 480))
        near(231.35f, m.glass.cx, "glass x")
        near(-337.30f, m.frame.left, "the frame's left: -156 × 2.16216")
        assertFalse("top right corner off the glass", m.visible(799f, 1f))
        assertTrue("top left corner still on it", m.visible(1f, 1f))
    }

    @Test
    fun `a cut picture shows only its window, even inside the glass's circle`() {
        // centerCrop at 80%: a 658×375 window at (71, 52) of the 800×480 picture fills the frame; 1.25095 and 1.25
        // Android Auto pixels per dash pixel. The glass at (71 + 263 × 1.25095, 52 + 263 × 1.25) = (400, 380.75),
        // radius 329. The frame's bottom is at 52 + 375 = 427: (400, 460) is inside the circle (79 from its centre) yet
        // below the frame, so it never reaches the dash.
        val m = checkNotNull(DashMask.of(FrameFit.centerCrop(whole800, 80), 800, 480, 800, 480))
        near(400f, m.glass.cx, "glass x")
        near(380.75f, m.glass.cy, "glass y")
        near(427f, m.frame.bottom, "the frame's bottom")
        assertTrue("inside the circle", m.glass.contains(400f, 460f))
        assertFalse("but below the frame", m.visible(400f, 460f))
        assertTrue("just above the frame's bottom", m.visible(400f, 420f))
    }

    @Test
    fun `no screen yet, no mask`() {
        assertNull(DashMask.of(FrameFit.wholeVisible(whole800), 800, 480, 0, 0))
        assertNotNull(DashMask.of(FrameFit.wholeVisible(whole800), 800, 480, 10, 10))
    }
}
