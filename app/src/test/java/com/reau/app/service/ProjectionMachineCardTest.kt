package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.Prepare
import com.reau.app.service.ProjectionEffect.Release
import com.reau.app.service.ProjectionEffect.StartAndroidAuto
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEffect.StartVideo
import com.reau.app.service.ProjectionEffect.StopAndroidAuto
import com.reau.app.service.ProjectionEffect.StopDash
import com.reau.app.service.ProjectionEffect.StopVideo
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.StartCard
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card mode: a still picture goes to the dash with no Android Auto. It runs the same life
 * as a projection: the "Android Auto side" is the card, which is up as soon as it is drawn. The machine only has to
 * remember which of the two was switched on, for the screen and the notification.
 */
class ProjectionMachineCardTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event)
        state = step.state
        return step.effects
    }

    @Test
    fun `switched on for the card, it gets ready like a projection and remembers the card`() {
        assertEquals(listOf(Prepare), on(StartCard))
        assertEquals(Phase.PREPARING, state.phase)
        assertTrue(state.card)
        assertEquals(listOf(StartAndroidAuto, StartDash), on(Prepared))
        assertTrue("still the card once running", state.card)
    }

    @Test
    fun `a projection switched on the usual way is not the card`() {
        on(Start)
        on(Prepared)
        assertFalse(state.card)
    }

    @Test
    fun `with the card drawn and the dash ready the video goes and the headline says it is the card`() {
        on(StartCard)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals(listOf(StartVideo), on(DashChanged(DashLink.READY)))
        assertTrue(state.streaming)
        assertEquals(Headline.CARD, state.headline)
    }

    @Test
    fun `the usual projection streaming still says streaming`() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
        assertEquals(Headline.STREAMING, state.headline)
    }

    @Test
    fun `switching off ends the card`() {
        on(StartCard)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
        assertEquals(listOf(StopVideo, StopAndroidAuto, StopDash, Release, ProjectionEffect.FinishStop), on(Stop))
        on(ProjectionEvent.StopDone)
        assertEquals(Projection(), state)
    }

    @Test
    fun `while one is on, switching on either is ignored`() {
        on(StartCard)
        on(Prepared)
        val running = state
        assertEquals(emptyList<ProjectionEffect>(), on(Start))
        assertEquals(emptyList<ProjectionEffect>(), on(StartCard))
        assertEquals(running, state)

        on(Stop)
        on(ProjectionEvent.StopDone)
        on(Start)
        on(Prepared)
        assertEquals(emptyList<ProjectionEffect>(), on(StartCard))
        assertFalse(state.card)
    }

    @Test
    fun `the card while still getting ready is stopped like a projection`() {
        on(StartCard)
        assertEquals(listOf(Release, ProjectionEffect.FinishStop), on(Stop))
        on(ProjectionEvent.StopDone)
        assertEquals(Projection(), state)
    }
}
