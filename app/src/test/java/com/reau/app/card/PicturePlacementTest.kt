package com.reau.app.card

import com.reau.app.video.RectPx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A picture chosen by the rider goes to the dash whole and unstretched: as large as the 526×300 frame allows,
 * centred, black around it. A picture made for the dash (526×300, like the bench test cards) fills it exactly.
 */
class PicturePlacementTest {

    @Test
    fun `a picture of the frame's own size fills it`() {
        assertEquals(RectPx(0, 0, 526, 300), PicturePlacement.inFrame(526, 300))
    }

    @Test
    fun `a wider shape touches the sides, centred top to bottom`() {
        assertEquals(RectPx(0, 75, 526, 150), PicturePlacement.inFrame(1052, 300))
    }

    @Test
    fun `a taller shape touches top and bottom, centred side to side`() {
        // 800x480 is 5:3; at 300 px tall it is 500 wide.
        assertEquals(RectPx(13, 0, 500, 300), PicturePlacement.inFrame(800, 480))
    }

    @Test
    fun `a small picture is scaled up`() {
        assertEquals(RectPx(113, 0, 300, 300), PicturePlacement.inFrame(100, 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty picture is refused`() {
        PicturePlacement.inFrame(0, 300)
    }
}
