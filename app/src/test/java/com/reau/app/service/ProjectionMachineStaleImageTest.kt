package com.reau.app.service

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.AndroidAutoImageStale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto streaming with a picture stuck while the bike moves: the dash repeats the last picture and looks alive, so
 * the notification and the screen say so instead of "Transmitindo". Nothing is stopped or started.
 */
class ProjectionMachineStaleImageTest {
    private val streaming = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY)

    @Test
    fun `a stuck picture says so in place of streaming, and the picture coming back clears it`() {
        val stale = ProjectionMachine.step(streaming, AndroidAutoImageStale(stale = true))
        assertEquals(Headline.ANDROID_AUTO_IMAGE_STALE, stale.state.headline)
        assertTrue("nothing stopped or started", stale.effects.isEmpty())
        assertEquals(DashLink.READY, stale.state.dash)
        assertEquals(Headline.STREAMING, ProjectionMachine.step(stale.state, AndroidAutoImageStale(stale = false)).state.headline)
    }

    @Test
    fun `a session that goes away takes the stuck picture with it`() {
        val stale = ProjectionMachine.step(streaming, AndroidAutoImageStale(stale = true)).state
        val dropped = ProjectionMachine.step(stale, AndroidAutoChanged(AaLinkState.DROPPED)).state
        assertFalse(dropped.aaImageStale)
        val back = ProjectionMachine.step(dropped, AndroidAutoChanged(AaLinkState.ACTIVE)).state
        assertEquals(Headline.STREAMING, back.headline)
    }

    @Test
    fun `the card mode and a stopped projection take no notice`() {
        assertEquals(Headline.CARD, ProjectionMachine.step(streaming.copy(card = true), AndroidAutoImageStale(true)).state.headline)
        assertEquals(Headline.STOPPED, ProjectionMachine.step(Projection(), AndroidAutoImageStale(true)).state.headline)
    }

    @Test
    fun `no picture at all stays the other warning`() {
        val both = ProjectionMachine.step(streaming.copy(aaNoVideo = true), AndroidAutoImageStale(true)).state
        assertEquals(Headline.ANDROID_AUTO_NO_VIDEO, both.headline)
    }

    @Test
    fun `the notification and the screen have their own text for it, in the warning colour`() {
        assertEquals(R.string.notif_title_image_stale to R.string.notif_text_image_stale, ProjectionNotification.contentFor(Headline.ANDROID_AUTO_IMAGE_STALE))
        assertEquals(ProjectionNotification.colorFor(Headline.DASH_LOST), ProjectionNotification.colorFor(Headline.ANDROID_AUTO_IMAGE_STALE))
        assertEquals(1, Headline.entries.count { ProjectionNotification.contentFor(it).first == R.string.notif_title_image_stale })
    }
}
