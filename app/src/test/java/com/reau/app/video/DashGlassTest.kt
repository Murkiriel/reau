package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What of the 526×300 frame the rider actually sees on the dash's round glass.
 *
 * Measured on a real dash with a numbered grid sent as a card: the glass is a circle of radius
 * 263 centred at (263, 263), and the video is its top 300 rows. The bike's own band is drawn under the video, not
 * over it. So the whole frame shows except its two top corners.
 */
class DashGlassTest {

    @Test
    fun `the frame is the one the dash decodes and the glass is a circle as wide as it`() {
        assertEquals(526, DashGlass.FRAME_W)
        assertEquals(300, DashGlass.FRAME_H)
        assertEquals(263, DashGlass.CENTER_X)
        assertEquals(263, DashGlass.CENTER_Y)
        assertEquals(263, DashGlass.RADIUS)
        assertEquals("nothing covers the picture's bottom", 300, DashGlass.USABLE_BOTTOM)
    }

    @Test
    fun `the visible width at each height is the measured table`() {
        val table = mapOf(
            0 to 0, 20 to 201, 40 to 279, 60 to 334, 80 to 378, 100 to 413, 150 to 475, 200 to 511, 250 to 525, 299 to 521,
        )
        for ((y, width) in table) assertEquals("y=$y", width, DashGlass.visibleWidthAt(y))
    }

    @Test
    fun `the photograph's readings fall on the circle`() {
        // Columns read on the grid photo, within the ±10 px a slanted photo allows.
        val read = mapOf(25 to (140 to 373), 50 to (107 to 415), 100 to (56 to 455), 150 to (28 to 499), 200 to (2 to 510))
        for ((y, cols) in read) {
            val half = DashGlass.visibleWidthAt(y) / 2
            assertEquals("left edge at y=$y", cols.first.toDouble(), (DashGlass.CENTER_X - half).toDouble(), 15.0)
            assertEquals("right edge at y=$y", cols.second.toDouble(), (DashGlass.CENTER_X + half).toDouble(), 15.0)
        }
    }

    @Test
    fun `nothing shows outside the frame, and the frame's bottom rows show from side to side`() {
        assertEquals(0, DashGlass.visibleWidthAt(-1))
        assertEquals(0, DashGlass.visibleWidthAt(300))
        assertTrue("never wider than the frame", (0 until 300).all { DashGlass.visibleWidthAt(it) <= 526 })
        assertTrue((240..299).all { DashGlass.visibleWidthAt(it) >= 515 })
    }

    @Test
    fun `the box holding what shows is the whole frame`() {
        assertEquals(RectPx(0, 0, 526, 300), DashGlass.DOME_BOX)
    }

    @Test
    fun `a point is visible inside the circle and inside the frame`() {
        assertTrue(DashGlass.isVisible(263.0, 150.0))
        assertTrue(DashGlass.isVisible(263.0, 1.0))
        assertTrue("the frame's bottom corner", DashGlass.isVisible(10.0, 299.0))
        assertTrue(DashGlass.isVisible(30.0, 150.0))

        assertFalse("the frame's top corner is outside the circle", DashGlass.isVisible(0.0, 0.0))
        assertFalse("left of the circle", DashGlass.isVisible(20.0, 150.0))
        assertFalse("under the frame", DashGlass.isVisible(263.0, 301.0))
        assertFalse("beside the frame, though inside the circle's square", DashGlass.isVisible(-1.0, 263.0))
    }

    @Test
    fun `how much of a rectangle shows`() {
        assertEquals(1.0, DashGlass.visibleFraction(RectPx(213, 100, 100, 50)), 1e-9)
        assertEquals("the top-left corner", 0.0, DashGlass.visibleFraction(RectPx(0, 0, 60, 40)), 1e-9)
        assertEquals("the band the first version thought covered", 1.0, DashGlass.visibleFraction(RectPx(200, 200, 100, 50)), 1e-9)
        // The whole frame: all but its two top corners.
        assertEquals(0.811, DashGlass.visibleFraction(DashGlass.DOME_BOX), 0.003)
    }

    @Test
    fun `a pixel counts as visible by its centre`() {
        // Row 150's leftmost pixel on the glass is 25: centre (25.5, 150.5) is 237.5 from the centre column, inside
        // the chord's 237.7; pixel 24 is not.
        assertEquals(1.0, DashGlass.visibleFraction(RectPx(25, 150, 1, 1)), 1e-9)
        assertEquals(0.0, DashGlass.visibleFraction(RectPx(24, 150, 1, 1)), 1e-9)
        // Row 0 shows only from pixel 247 to 278.
        assertEquals(1.0, DashGlass.visibleFraction(RectPx(247, 0, 32, 1)), 1e-9)
        assertEquals(0.0, DashGlass.visibleFraction(RectPx(246, 0, 1, 1)), 1e-9)
    }
}
