package com.reau.app.service

import com.reau.app.dash.DashState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dash session's state flow hands its first listener the IDLE it starts in,
 * and that listener starts on another thread, so the IDLE could reach the machine after it had already asked for the
 * dash. Read as "the session went away by itself", it scheduled a retry in 2 s that cancelled the Wi-Fi request still
 * under way (Android's network picker twice at every start). Only the IDLE before any activity is dropped: an IDLE
 * after the session was up is a real drop and goes through.
 */
class ProjectionDashLinksTest {
    private fun links(vararg states: DashState) = runBlocking { ProjectionMachine.dashLinks(flowOf(*states)).toList() }

    @Test
    fun `the idle the session starts in is not reported`() {
        assertEquals(emptyList<DashLink>(), links(DashState.IDLE))
        assertEquals(listOf(DashLink.CONNECTING, DashLink.READY), links(DashState.IDLE, DashState.CONNECTING, DashState.READY))
    }

    @Test
    fun `an idle after the session was up is a real drop and is reported`() {
        assertEquals(
            listOf(DashLink.CONNECTING, DashLink.READY, DashLink.OFF),
            links(DashState.IDLE, DashState.CONNECTING, DashState.READY, DashState.IDLE),
        )
    }

    @Test
    fun `a failure right away is reported`() {
        assertEquals(listOf(DashLink.DROPPED), links(DashState.IDLE, DashState.ERROR))
    }

    @Test
    fun `the machine itself still reads an idle while running as a drop`() {
        val running = ProjectionMachine.step(ProjectionMachine.step(Projection(), ProjectionEvent.Start).state, ProjectionEvent.Prepared).state
        val step = ProjectionMachine.step(running.copy(dash = DashLink.READY), ProjectionEvent.DashChanged(DashLink.OFF))
        assertEquals(DashLink.DROPPED, step.state.dash)
    }
}
