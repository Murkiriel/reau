package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto only renders a handful of fixed frame sizes; the margins a head unit advertises make it draw its
 * screen in a smaller area, centred inside that frame, so the picture already has the shape the panel needs.
 */
class AaMarginsTest {

    @Test
    fun `for the dome an 800x480 frame loses 26 px of width`() {
        assertEquals(AaMargins(26, 0), AaMargins.forAspect(800, 480, 300, 186))
    }

    @Test
    fun `for a square an 800x480 frame loses 320 px of width`() {
        assertEquals(AaMargins(320, 0), AaMargins.forAspect(800, 480, 1, 1))
    }

    @Test
    fun `a portrait frame is trimmed in height instead`() {
        assertEquals(AaMargins(0, 560), AaMargins.forAspect(720, 1280, 1, 1))
    }

    @Test
    fun `a frame that already has the target shape needs no margin`() {
        assertEquals(AaMargins.NONE, AaMargins.forAspect(800, 480, 5, 3))
        assertFalse(AaMargins.NONE.any)
        assertTrue(AaMargins(26, 0).any)
    }

    @Test
    fun `a senseless target gives no margin rather than a senseless one`() {
        assertEquals(AaMargins.NONE, AaMargins.forAspect(800, 480, 0, 186))
        assertEquals(AaMargins.NONE, AaMargins.forAspect(800, 480, 300, -1))
    }

    @Test
    fun `the usable area never shrinks below 16 px`() {
        assertEquals(AaMargins(800 - 16, 0), AaMargins.forAspect(800, 480, 1, 1000))
        assertEquals(AaMargins(0, 480 - 16), AaMargins.forAspect(800, 480, 1000, 1))
    }

    @Test
    fun `the content sits centred in the coded frame`() {
        assertEquals(RectPx(13, 0, 774, 480), AaMargins(26, 0).contentRect(800, 480))
        assertEquals(RectPx(160, 0, 480, 480), AaMargins(320, 0).contentRect(800, 480))
        assertEquals(RectPx(0, 280, 720, 720), AaMargins(0, 560).contentRect(720, 1280))
        assertEquals(RectPx(0, 0, 800, 480), AaMargins.NONE.contentRect(800, 480))
    }
}
