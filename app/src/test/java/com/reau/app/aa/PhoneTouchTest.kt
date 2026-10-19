package com.reau.app.aa

import com.reau.app.video.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a finger on the phone's "Tela do Android Auto" lands on Android Auto's picture:
 * the screen shows the whole picture in [viewport] (as big as fits, centred), so the finger is scaled back into the
 * video's own pixels, which is what the announced touch screen measures.
 */
class PhoneTouchTest {
    // 800x480 shown in 1080x648 at (0, 636) of a portrait phone (CompositorMath.previewViewport)
    private val viewport = RectPx(0, 636, 1080, 648)

    @Test
    fun `a finger on the picture lands on the same spot of the video`() {
        assertEquals(0 to 0, PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 0f, 636f), viewport, 800, 480))
        assertEquals(400 to 240, PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 540f, 960f), viewport, 800, 480))
        assertEquals(799 to 479, PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 1079.9f, 1283.9f), viewport, 800, 480))
    }

    @Test
    fun `a finger that starts off the picture does nothing`() {
        assertNull(PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 540f, 100f), viewport, 800, 480)) // the black band above
        assertNull(PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 540f, 1300f), viewport, 800, 480))
    }

    @Test
    fun `a drag that leaves the picture goes on and ends at its edge`() {
        assertEquals(400 to 0, PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.MOVE, 540f, 100f), viewport, 800, 480))
        assertEquals(799 to 479, PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.UP, 5000f, 5000f), viewport, 800, 480))
    }

    @Test
    fun `a stroke only moves and lifts a finger that went down on the picture`() {
        val stroke = PhoneTouch.Stroke()
        fun at(action: PhoneTouch.Action, x: Float, y: Float) = stroke.next(PhoneTouch(action, x, y), viewport, 800, 480)
        // started on the black band: the whole stroke is dropped, even when it then crosses the picture
        assertNull(at(PhoneTouch.Action.DOWN, 540f, 100f))
        assertNull(at(PhoneTouch.Action.MOVE, 540f, 960f))
        assertNull(at(PhoneTouch.Action.UP, 540f, 960f))
        // started on the picture: down, moves, up (the up may land off the picture: clamped)
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.DOWN, 400, 240), at(PhoneTouch.Action.DOWN, 540f, 960f))
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.MOVE, 400, 0), at(PhoneTouch.Action.MOVE, 540f, 100f))
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.UP, 400, 0), at(PhoneTouch.Action.UP, 540f, 100f))
        // and the next stroke starts clean
        assertNull(at(PhoneTouch.Action.MOVE, 540f, 960f))
    }

    @Test
    fun `a stroke that is cancelled ends there, and a viewer that vanishes mid-stroke can be cancelled at its last point`() {
        val stroke = PhoneTouch.Stroke()
        fun at(action: PhoneTouch.Action, x: Float, y: Float) = stroke.next(PhoneTouch(action, x, y), viewport, 800, 480)
        assertNull("nothing down, nothing to cancel", stroke.cancel())
        at(PhoneTouch.Action.DOWN, 540f, 960f)
        at(PhoneTouch.Action.MOVE, 540f, 100f)
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.CANCEL, 400, 0), stroke.cancel())
        assertNull("only once", stroke.cancel())
        assertNull("what comes after is no part of a stroke", at(PhoneTouch.Action.MOVE, 540f, 960f))
        // a CANCEL from the finger ends the stroke like a lift
        at(PhoneTouch.Action.DOWN, 540f, 960f)
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.CANCEL, 400, 240), at(PhoneTouch.Action.CANCEL, 540f, 960f))
        assertNull(stroke.cancel())
        assertNull(at(PhoneTouch.Action.MOVE, 540f, 960f))
    }

    @Test
    fun `no picture yet, no touch`() {
        assertNull(PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 10f, 10f), RectPx(0, 0, 0, 0), 800, 480))
    }
}
