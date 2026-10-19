package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.FinishStop
import com.reau.app.service.ProjectionEffect.Release
import com.reau.app.service.ProjectionEffect.StopAndroidAuto
import com.reau.app.service.ProjectionEffect.StopDash
import com.reau.app.service.ProjectionEffect.StopVideo
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.NetworkServer
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.StartCard
import com.reau.app.service.ProjectionEvent.Stop
import com.reau.app.service.ProjectionEvent.StopDone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Desligando": between the rider's Desligar and the end of its effects the projection is stopped for everything that
 * reads the phase, but says it is stopping, and a start is not taken until the stop is done. A ride once had a Ligar
 * 1.7 s after Desligar waited behind a stop that took 2.5 s, with the screen already saying "Parado".
 */
class ProjectionMachineStoppingTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event)
        state = step.state
        return step.effects
    }

    private fun streaming() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
    }

    @Test
    fun `Parado, Desligando, Parado`() {
        assertEquals(Headline.STOPPED, state.headline)
        streaming()
        assertEquals(Headline.STREAMING, state.headline)
        assertEquals(listOf(StopVideo, StopAndroidAuto, StopDash, Release, FinishStop), on(Stop))
        assertEquals(Headline.STOPPING, state.headline)
        assertTrue(state.stopping)
        assertEquals("stopped for everything that reads the phase", Phase.STOPPED, state.phase)
        assertTrue(on(StopDone).isEmpty())
        assertEquals(Projection(), state)
        assertEquals(Headline.STOPPED, state.headline)
    }

    @Test
    fun `the stop effects always end with the finish, even from a start that was still preparing`() {
        on(Start)
        assertEquals(listOf(Release, FinishStop), on(Stop))
        assertEquals(Headline.STOPPING, state.headline)
        on(StopDone)
        assertEquals(Projection(), state)
    }

    @Test
    fun `Ligar while it is turning off starts nothing`() {
        streaming()
        on(Stop)
        val stopping = state
        assertTrue(on(Start).isEmpty())
        assertEquals(stopping, state)
        assertTrue(on(StartCard).isEmpty())
        assertEquals(stopping, state)
        assertEquals(Headline.STOPPING, state.headline)
    }

    @Test
    fun `once the stop is done a start is taken as always`() {
        streaming()
        on(Stop)
        on(StopDone)
        assertEquals(listOf(ProjectionEffect.Prepare), on(Start))
        assertEquals(Phase.PREPARING, state.phase)
        assertFalse(state.stopping)
    }

    @Test
    fun `a second Desligar while it is turning off does nothing`() {
        streaming()
        on(Stop)
        val stopping = state
        assertTrue(on(Stop).isEmpty())
        assertEquals(stopping, state)
    }

    @Test
    fun `what the sides report while it is turning off changes nothing`() {
        streaming()
        on(Stop)
        val stopping = state
        assertTrue(on(AndroidAutoChanged(AaLinkState.IDLE)).isEmpty())
        assertTrue(on(AndroidAutoChanged(AaLinkState.ACTIVE)).isEmpty())
        assertTrue(on(DashChanged(DashLink.DROPPED)).isEmpty())
        assertTrue(on(DashChanged(DashLink.READY)).isEmpty())
        assertTrue(on(NetworkServer(true)).isEmpty())
        assertEquals(stopping, state)
    }

    @Test
    fun `a done stop that nobody asked for changes nothing`() {
        assertTrue(on(StopDone).isEmpty())
        assertEquals(Projection(), state)
        streaming()
        val streaming = state
        assertTrue(on(StopDone).isEmpty())
        assertEquals(streaming, state)
    }

    @Test
    fun `a start that failed is not a stop in progress`() {
        on(Start)
        on(ProjectionEvent.PrepareFailed("no wake lock"))
        assertEquals(Headline.PROBLEM, state.headline)
        assertFalse(state.stopping)
    }
}
