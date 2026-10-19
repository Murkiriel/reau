package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.StartAndroidAuto
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.StartCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto first: the dash is only looked for once Android Auto's session is up; the card mode, which has no
 * Android Auto, goes to the dash at once.
 */
class ProjectionMachineAaFirstTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event)
        state = step.state
        return step.effects
    }

    @Test
    fun `once ready only Android Auto is started, and the dash waits for it`() {
        on(Start)
        assertEquals(listOf(StartAndroidAuto), on(Prepared))
        assertEquals(DashLink.OFF, state.dash)
        assertEquals(Headline.CONNECTING, state.headline)
    }

    @Test
    fun `the dash is started when Android Auto's session is up, once`() {
        on(Start)
        on(Prepared)
        assertTrue("still connecting", on(AndroidAutoChanged(AaLinkState.CONNECTING)).isEmpty())
        assertTrue("no server yet", on(AndroidAutoChanged(AaLinkState.SERVER_OFF)).isEmpty())
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE)))
        assertEquals(DashLink.CONNECTING, state.dash)
        on(AndroidAutoChanged(AaLinkState.DROPPED))
        assertTrue("a dash already on its way is not started again", on(AndroidAutoChanged(AaLinkState.ACTIVE)).isEmpty())
    }

    @Test
    fun `a dash retry that comes due with Android Auto down waits for Android Auto`() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.DROPPED))
        on(AndroidAutoChanged(AaLinkState.DROPPED))
        assertTrue("not now", on(DashRetryDue).isEmpty())
        assertEquals(DashLink.OFF, state.dash)
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE)))
    }

    @Test
    fun `the card mode has no Android Auto and goes to the dash at once`() {
        on(StartCard)
        assertEquals(listOf(StartAndroidAuto, StartDash), on(Prepared))
        assertEquals(DashLink.CONNECTING, state.dash)
    }
}
