package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.Release
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEffect.StopAndroidAuto
import com.reau.app.service.ProjectionEffect.StopDash
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dash held by another app: its port is taken, so the projection does not connect. The rider is told
 * so, and the dash is tried again on the same timer as a dropped one, so the projection carries on by itself once
 * the other app lets go.
 */
class ProjectionMachineTakenTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event)
        state = step.state
        return step.effects
    }

    private fun running() {
        on(Start)
        on(Prepared)
    }

    @Test
    fun `a taken dash is said so and tried again after two seconds`() {
        running()
        assertEquals(listOf(RetryDashIn(2_000)), on(DashChanged(DashLink.TAKEN)))
        assertEquals(DashLink.TAKEN, state.dash)
        assertEquals(1, state.dashRetries)
        assertEquals(Headline.DASH_TAKEN, state.headline)
    }

    @Test
    fun `when the wait is over the dash is tried again`() {
        // The dash waits for Android Auto's session (ProjectionMachineAaFirstTest).
        running()
        on(ProjectionEvent.AndroidAutoChanged(com.reau.app.aa.AaLinkState.ACTIVE))
        on(DashChanged(DashLink.TAKEN))
        assertEquals(listOf(StartDash), on(DashRetryDue))
        assertEquals(DashLink.CONNECTING, state.dash)
    }

    @Test
    fun `taken again and again, the wait grows like a dropped dash's`() {
        running()
        val effects = (1..6).map {
            on(DashChanged(DashLink.TAKEN)).also { on(DashRetryDue) }
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L).map { listOf(RetryDashIn(it)) }, effects)
    }

    @Test
    fun `once the other app lets go the dash comes up and the count starts over`() {
        running()
        on(DashChanged(DashLink.TAKEN))
        on(DashRetryDue)
        on(DashChanged(DashLink.READY))
        assertEquals(DashLink.READY, state.dash)
        assertEquals(0, state.dashRetries)
    }

    @Test
    fun `Android Auto's server off is still said first, then the taken dash, before anything else`() {
        running()
        on(DashChanged(DashLink.TAKEN))
        on(AndroidAutoChanged(AaLinkState.SERVER_OFF))
        assertEquals(Headline.ANDROID_AUTO_SERVER_OFF, state.headline)
        on(AndroidAutoChanged(AaLinkState.CONNECTING))
        assertEquals(Headline.DASH_TAKEN, state.headline)
    }

    @Test
    fun `switching off with the dash taken stops everything and forgets it`() {
        running()
        on(DashChanged(DashLink.TAKEN))
        assertEquals(listOf(StopAndroidAuto, StopDash, Release, ProjectionEffect.FinishStop), on(Stop))
        on(ProjectionEvent.StopDone)
        assertEquals(Projection(), state)
    }

    @Test
    fun `a taken dash heard while stopped changes nothing`() {
        assertEquals(emptyList<ProjectionEffect>(), on(DashChanged(DashLink.TAKEN)))
        assertEquals(Projection(), state)
    }
}
