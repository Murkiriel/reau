package com.reau.app.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The link between the preview screen and the running compositor. */
class PhonePreviewTest {
    private val seen = mutableListOf<PhonePreview.Shown?>()
    private val target: (PhonePreview.Shown?) -> Unit = { seen += it }

    @After
    fun tearDown() {
        PhonePreview.detach(target)
        PhonePreview.detachTouch(touchTarget)
        PhonePreview.hide()
    }

    private val touches = mutableListOf<Pair<com.reau.app.aa.PhoneTouch, PhonePreview.Shown?>>()
    private val touchTarget: (com.reau.app.aa.PhoneTouch, PhonePreview.Shown?) -> Unit = { t, s -> touches += t to s }

    @Test
    fun `a finger on the screen reaches the projection with the screen's size, and nothing once it left`() {
        val surface = Any()
        PhonePreview.show(surface, 1080, 1920)
        val down = com.reau.app.aa.PhoneTouch(com.reau.app.aa.PhoneTouch.Action.DOWN, 540f, 960f)
        PhonePreview.touch(down) // nobody listens yet: dropped
        PhonePreview.attachTouch(touchTarget)
        PhonePreview.touch(down)
        assertEquals(listOf(down to PhonePreview.Shown(surface, 1080, 1920)), touches)
        PhonePreview.detachTouch(touchTarget)
        PhonePreview.touch(down)
        assertEquals(1, touches.size)
    }

    @Test
    fun `a screen opened before the projection gets the picture as soon as the compositor is there`() {
        val surface = Any()
        PhonePreview.show(surface, 1080, 648)
        PhonePreview.attach(target)
        assertEquals(listOf(PhonePreview.Shown(surface, 1080, 648)), seen)
    }

    @Test
    fun `opening, resizing and closing the screen reach the running compositor`() {
        PhonePreview.attach(target)
        assertEquals(listOf<PhonePreview.Shown?>(null), seen) // nothing shown yet: no preview
        val surface = Any()
        PhonePreview.show(surface, 1080, 648)
        PhonePreview.show(surface, 2400, 1080)
        PhonePreview.hide()
        assertEquals(listOf(null, PhonePreview.Shown(surface, 1080, 648), PhonePreview.Shown(surface, 2400, 1080), null), seen)
    }

    @Test
    fun `a compositor that is gone hears nothing more`() {
        PhonePreview.attach(target)
        PhonePreview.detach(target)
        PhonePreview.show(Any(), 10, 10)
        assertEquals(listOf<PhonePreview.Shown?>(null), seen)
        assertNull(PhonePreview.attachedForTest)
    }
}
