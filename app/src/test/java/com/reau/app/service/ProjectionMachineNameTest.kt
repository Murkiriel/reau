package com.reau.app.service

import com.reau.app.service.ProjectionEffect.StartVideo
import com.reau.app.service.ProjectionEffect.StopVideo
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dash's full Wi-Fi name nowhere to be had: the prefix alone would
 * be rejected by the dash in every handshake, so the projection does not try. The rider is told to type the whole
 * name, and nothing is tried again by itself: each try by the prefix opens Android's network picker on the phone.
 */
class ProjectionMachineNameTest {
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
    fun `a dash whose name is not known is said so, and not tried again by itself`() {
        running()
        assertEquals(emptyList<ProjectionEffect>(), on(DashChanged(DashLink.NAME_NEEDED)))
        assertEquals(DashLink.NAME_NEEDED, state.dash)
        assertEquals(Headline.DASH_NAME_NEEDED, state.headline)
        assertTrue("no retry is pending, so none is due", on(DashRetryDue).isEmpty())
        assertEquals(DashLink.NAME_NEEDED, state.dash)
    }

    @Test
    fun `the video stops if it was going`() {
        running()
        on(DashChanged(DashLink.READY))
        assertEquals(listOf(StopVideo), on(DashChanged(DashLink.NAME_NEEDED)))
    }

    @Test
    fun `once the rider fixes the name a new start connects as usual`() {
        running()
        on(DashChanged(DashLink.NAME_NEEDED))
        on(ProjectionEvent.Stop)
        on(ProjectionEvent.StopDone)
        running()
        assertEquals(listOf(StartVideo), on(DashChanged(DashLink.READY)))
    }
}
