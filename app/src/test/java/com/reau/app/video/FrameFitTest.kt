package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which part of Android Auto's frame goes where on the dash's 526×300 frame. Three ways: the whole frame small enough
 * to be seen whole, the whole frame filling the dome, and a window cut from the middle of the frame, larger on the
 * glass.
 */
class FrameFitTest {
    private val whole800 = RectPx(0, 0, 800, 480)

    // ── the whole frame, all of it visible ─────────────────────────────────────────────────────────────────

    @Test
    fun `whole and visible, an 800x480 frame becomes 370x222 resting on the frame's bottom edge`() {
        val m = FrameFit.wholeVisible(whole800)

        assertEquals(whole800, m.source)
        assertEquals(RectPx(78, 78, 370, 222), m.dest)
        assertEquals(DashGlass.FRAME_H, m.dest.bottom)
        assertEquals(0.4625, m.scale, 0.001)
    }

    @Test
    fun `whole and visible means every corner is on the glass, and one more step would not be`() {
        val d = FrameFit.wholeVisible(whole800).dest
        for ((x, y) in listOf(d.x to d.y, d.right to d.y, d.x to d.bottom, d.right to d.bottom)) {
            assertTrue("corner ($x, $y)", DashGlass.isVisible(x.toDouble(), y.toDouble()))
        }
        assertEquals(1.0, DashGlass.visibleFraction(d), 1e-9)
        // The next size up at the same shape, 372 × 223.2, already has its top corners off the glass.
        assertFalse(DashGlass.isVisible((d.x - 1).toDouble(), DashGlass.FRAME_H - 223.2))
    }

    @Test
    fun `a square frame, whole and visible, is smaller still`() {
        val m = FrameFit.wholeVisible(RectPx(160, 0, 480, 480))

        assertEquals(RectPx(160, 0, 480, 480), m.source)
        assertEquals(RectPx(131, 36, 264, 264), m.dest)
        assertEquals(1.0, DashGlass.visibleFraction(m.dest), 1e-9)
    }

    @Test
    fun `a very wide frame, whole and visible, is held by its bottom corners instead`() {
        // 6.4:1. On the frame's bottom row the circle is 521 wide, so the strip cannot be the full 526.
        val m = FrameFit.wholeVisible(RectPx(0, 0, 1280, 200))

        assertEquals(RectPx(4, 219, 518, 81), m.dest)
        assertEquals(1.0, DashGlass.visibleFraction(m.dest), 1e-9)
    }

    // ── the whole frame, filling the frame ─────────────────────────────────────────────────────────────────

    @Test
    fun `filling, an 800x480 frame is 500x300 at (13, 0), the default`() {
        val m = FrameFit.fillDome(whole800)

        assertEquals(whole800, m.source)
        assertEquals(RectPx(13, 0, 500, 300), m.dest)
        assertEquals(0.625, m.scale, 1e-9)
        assertEquals("the circle cuts its top corners", 0.838, DashGlass.visibleFraction(m.dest), 0.003)
    }

    @Test
    fun `filling never passes the frame's edges`() {
        val square = FrameFit.fillDome(RectPx(160, 0, 480, 480))
        assertEquals("limited by the frame's height", RectPx(113, 0, 300, 300), square.dest)

        val wide = FrameFit.fillDome(RectPx(0, 0, 1280, 500))
        assertEquals("limited by the frame's width, resting on its bottom", RectPx(0, 95, 526, 205), wide.dest)
    }

    @Test
    fun `filling with a picture of the frame's own shape uses all of it`() {
        val m = FrameFit.fillDome(AaMargins.forAspect(800, 480, 526, 300).contentRect(800, 480))

        assertEquals(RectPx(0, 12, 800, 456), m.source)
        assertEquals(DashGlass.DOME_BOX, m.dest)
    }

    // ── a window cut from the middle ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a centre crop at 80 percent takes a 658x375 window from the middle and fills the frame with it`() {
        val m = FrameFit.centerCrop(whole800, scalePercent = 80)

        assertEquals(RectPx(71, 52, 658, 375), m.source)
        assertEquals(DashGlass.DOME_BOX, m.dest)
        assertEquals(0.8, m.scale, 0.001)
    }

    @Test
    fun `at 100 percent the window is the frame's own size, one source pixel per dash pixel`() {
        val m = FrameFit.centerCrop(whole800, scalePercent = 100)

        assertEquals(RectPx(137, 90, 526, 300), m.source)
        assertEquals(1.0, m.scale, 1e-9)
    }

    @Test
    fun `a scale too small for the frame stops where the window is as large as the frame allows`() {
        val m = FrameFit.centerCrop(whole800, scalePercent = 10)

        assertEquals("the full width, top and bottom trimmed to the frame's shape", RectPx(0, 12, 800, 456), m.source)
        assertEquals(DashGlass.DOME_BOX, m.dest)
        assertEquals("about 66% is already the whole width", m, FrameFit.centerCrop(whole800, scalePercent = 65))
    }

    @Test
    fun `a scale above 100 percent is taken as 100`() {
        assertEquals(FrameFit.centerCrop(whole800, 100), FrameFit.centerCrop(whole800, 250))
    }

    @Test
    fun `the window can be moved off centre`() {
        assertEquals(RectPx(0, 0, 658, 375), FrameFit.centerCrop(whole800, 80, centerXPercent = 0, centerYPercent = 0).source)
        assertEquals(RectPx(142, 105, 658, 375), FrameFit.centerCrop(whole800, 80, centerXPercent = 100, centerYPercent = 100).source)
        assertEquals(RectPx(71, 78, 658, 375), FrameFit.centerCrop(whole800, 80, centerYPercent = 75).source)
    }

    // ── the window placed by its top left pixel ────────────────────────────────────────────────────────────

    @Test
    fun `the crop's window size is the one centerCrop uses`() {
        assertEquals(658 to 375, FrameFit.cropSize(whole800, 80))
        assertEquals(711 to 405, FrameFit.cropSize(whole800, 74))
    }

    @Test
    fun `a window placed by pixels lands exactly there, centred when not placed`() {
        assertEquals(RectPx(89, 75, 711, 405), FrameFit.cropAt(whole800, 74, 89, 75).source)
        assertEquals("centred, as centerCrop at 50%", FrameFit.centerCrop(whole800, 74).source, FrameFit.cropAt(whole800, 74, null, null).source)
        assertEquals(RectPx(10, 37, 711, 405), FrameFit.cropAt(whole800, 74, 10, null).source)
        assertEquals(DashGlass.DOME_BOX, FrameFit.cropAt(whole800, 74, 0, 0).dest)
    }

    @Test
    fun `a window placed past the edge is marked so, and the margins move it with the content`() {
        assertTrue(FrameFit.cropAt(whole800, 74, 120, 0).pastEdge)
        assertTrue(FrameFit.cropAt(whole800, 74, 0, -1).pastEdge)
        assertFalse(FrameFit.cropAt(whole800, 74, 89, 75).pastEdge)
        assertEquals(RectPx(30, 20, 658, 375), FrameFit.cropAt(RectPx(20, 10, 760, 460), 80, 10, 10).source)
    }

    @Test
    fun `the window may go past the edge by all of it but one pixel`() {
        // Up to the whole window to either side (it was half of it): 711 wide,
        // from -710 to 799 across; 405 tall, from -404 to 479 down. One pixel stays on the picture: all of it outside
        // is a black dash, which FrameFit.problems refuses.
        assertEquals(-710..799 to -404..479, FrameFit.cropRange(whole800, 74))
        for (x in listOf(-710, 799)) {
            assertEquals("x $x", emptyList<FrameFit.Problem>(), FrameFit.problems(FrameFit.cropAt(whole800, 74, x, 479), 800, 480))
        }
    }

    @Test
    fun `the window can go past the picture's edge, from -100 to 200 percent of the slack`() {
        // Values above 100% and below 0% are accepted: the window used to stop at 0 and 100. At 80% it is 658×375, a
        // slack of 142 × 105.
        assertEquals(RectPx(284, -105, 658, 375), FrameFit.centerCrop(whole800, 80, centerXPercent = 200, centerYPercent = -100).source)
        assertEquals(RectPx(-142, 210, 658, 375), FrameFit.centerCrop(whole800, 80, centerXPercent = -100, centerYPercent = 200).source)
        assertEquals("stops at 200", FrameFit.centerCrop(whole800, 80, 200, 200), FrameFit.centerCrop(whole800, 80, 400, 900))
        assertEquals("stops at -100", FrameFit.centerCrop(whole800, 80, -100, -100), FrameFit.centerCrop(whole800, 80, -300, -900))
        assertEquals(listOf<FrameFit.Problem>(), FrameFit.problems(FrameFit.centerCrop(whole800, 80, 200, -100), 800, 480))
        assertTrue("marked as past the edge", FrameFit.centerCrop(whole800, 80, 200, -100).pastEdge)
        assertFalse("within 0 and 100 it is not", FrameFit.centerCrop(whole800, 80, 100, 0).pastEdge)
    }

    @Test
    fun `the window is cut from the content area when Android Auto was given margins`() {
        val content = AaMargins(320, 0).contentRect(800, 480) // 480×480 at x = 160
        val m = FrameFit.centerCrop(content, scalePercent = 100)

        assertEquals("narrower than the frame: as large as the content allows", RectPx(160, 103, 480, 274), m.source)
    }

    // ── checking a mapping someone typed in ────────────────────────────────────────────────────────────────

    @Test
    fun `the three fits are valid mappings`() {
        for (m in listOf(FrameFit.wholeVisible(whole800), FrameFit.fillDome(whole800), FrameFit.centerCrop(whole800, 80))) {
            assertEquals(m.toString(), emptyList<FrameFit.Problem>(), FrameFit.problems(m, 800, 480))
        }
    }

    @Test
    fun `a mapping is refused for each thing that would put garbage on the dash`() {
        fun problems(source: RectPx, dest: RectPx) = FrameFit.problems(FrameFit.Mapping(source, dest), 800, 480)

        assertEquals(listOf(FrameFit.Problem.SOURCE_OUTSIDE_FRAME), problems(RectPx(400, 0, 480, 300), RectPx(23, 0, 480, 300)))
        assertEquals(listOf(FrameFit.Problem.SOURCE_OUTSIDE_FRAME), problems(RectPx(-1, 0, 526, 300), DashGlass.DOME_BOX))
        // only a crop slid past the edge on purpose may leave the frame, and not wholly
        fun past(source: RectPx) = FrameFit.problems(FrameFit.Mapping(source, DashGlass.DOME_BOX, pastEdge = true), 800, 480)
        assertEquals(listOf<FrameFit.Problem>(), past(RectPx(-1, 0, 526, 300)))
        assertEquals(listOf(FrameFit.Problem.SOURCE_OUTSIDE_FRAME), past(RectPx(800, 0, 526, 300)))
        assertEquals(listOf(FrameFit.Problem.SOURCE_OUTSIDE_FRAME), past(RectPx(-526, 0, 526, 300)))
        assertEquals(listOf(FrameFit.Problem.DEST_OUTSIDE_FRAME), problems(RectPx(0, 0, 300, 186), RectPx(300, 0, 300, 186)))
        assertEquals(listOf(FrameFit.Problem.TOO_SMALL), problems(RectPx(0, 0, 15, 15), RectPx(256, 100, 15, 15)))
        assertEquals(listOf(FrameFit.Problem.DISTORTED), problems(RectPx(0, 0, 800, 480), RectPx(113, 0, 300, 100)))
        assertEquals(listOf(FrameFit.Problem.NOT_VISIBLE), problems(RectPx(0, 0, 100, 50), RectPx(0, 0, 100, 50)))
    }

    @Test
    fun `a little distortion from whole-pixel rounding is not a problem`() {
        // 800×456 into 526×300: 1.7544 against 1.7533.
        assertEquals(emptyList<FrameFit.Problem>(), FrameFit.problems(FrameFit.Mapping(RectPx(0, 12, 800, 456), DashGlass.DOME_BOX), 800, 480))
    }

    // ── moving the picture ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `moving the picture moves only its place on the dash, down and right being positive`() {
        val whole = FrameFit.wholeVisible(whole800) // 370x222 at (78, 78)
        val moved = FrameFit.moved(whole, -10, -40)
        assertEquals(whole.source, moved.source)
        assertEquals(RectPx(68, 38, 370, 222), moved.dest)
        assertEquals(whole, FrameFit.moved(whole, 0, 0))
    }

    @Test
    fun `a moved picture stops at the edges of the dash's frame and is always a valid mapping`() {
        val whole = FrameFit.wholeVisible(whole800)
        assertEquals(RectPx(78, 0, 370, 222), FrameFit.moved(whole, 0, -150).dest)
        assertEquals("already on the bottom edge", RectPx(78, 78, 370, 222), FrameFit.moved(whole, 0, 150).dest)
        assertEquals(RectPx(0, 78, 370, 222), FrameFit.moved(whole, -150, 0).dest)
        assertEquals(RectPx(156, 78, 370, 222), FrameFit.moved(whole, 150, 0).dest)
        val filled = FrameFit.fillDome(whole800)
        assertEquals("as tall as the frame, it cannot go up or down", filled, FrameFit.moved(filled, 0, 40))
        for (m in listOf(whole, filled)) for (dx in listOf(-150, 0, 150)) for (dy in listOf(-150, 0, 150)) {
            assertEquals("moved by ($dx, $dy)", emptyList<FrameFit.Problem>(), FrameFit.problems(FrameFit.moved(m, dx, dy), 800, 480))
        }
    }
}
