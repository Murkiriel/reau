package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.CancelDashRetry
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.BikeNetworkAvailable
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bike's Wi-Fi coming back while a dropped dash waits for its retry. Observed in a ride log: the wait of a long absence
 * (a minute) was armed at 22:05:05, the bike's network was back at 22:05:12 and the dash was only tried at 22:06:05.
 * The wait is for a dash that is not there; the Wi-Fi returning is the news that it is.
 */
class ProjectionMachineBikeNetworkTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent, nowMs: Long = 0L): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event, nowMs)
        state = step.state
        return step.effects
    }

    private fun running() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
    }

    /** The dash dropped, tried again, and dropped again six minutes after it went: the wait is the minute of a long absence. */
    private fun droppedForLong(): Long {
        running()
        on(DashChanged(DashLink.DROPPED), nowMs = 0L)
        on(DashRetryDue, nowMs = 2_000L)
        val at = 6 * 60_000L
        val wait = on(DashChanged(DashLink.DROPPED), at).filterIsInstance<RetryDashIn>().single().delayMs
        assertEquals(60_000L, wait)
        assertEquals(DashLink.DROPPED, state.dash)
        return at
    }

    @Test
    fun `a dropped dash waiting out a minute is tried at once when the bike's Wi-Fi is back`() {
        val at = droppedForLong()
        // The 60 s wait of the log is armed; seven seconds later the Wi-Fi is back: the wait is called off, the dash tried now.
        assertEquals(listOf(CancelDashRetry, StartDash), on(BikeNetworkAvailable, at + 7_000L))
        assertEquals(DashLink.CONNECTING, state.dash)
    }

    @Test
    fun `the waits start over after the early try, not at the minute of the long absence`() {
        val at = droppedForLong()
        on(BikeNetworkAvailable, at + 7_000L)
        assertEquals(0, state.dashRetries)
        assertNull(state.dashGoneAtMs)
        val wait = on(DashChanged(DashLink.DROPPED), at + 9_000L).filterIsInstance<RetryDashIn>().single().delayMs
        assertEquals("the first wait again", 2_000L, wait)
    }

    @Test
    fun `nothing changes when no retry is waiting`() {
        // Not running, still preparing, connecting, ready, taken by another app: the Wi-Fi is none of their business.
        assertTrue(on(BikeNetworkAvailable).isEmpty())
        assertEquals(Projection(), state)

        on(Start)
        assertTrue(on(BikeNetworkAvailable).isEmpty())
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        val connecting = state
        assertEquals(DashLink.CONNECTING, connecting.dash)
        assertTrue(on(BikeNetworkAvailable).isEmpty())
        assertEquals(connecting, state)

        on(DashChanged(DashLink.READY))
        val ready = state
        assertTrue(on(BikeNetworkAvailable).isEmpty())
        assertEquals(ready, state)

        on(DashChanged(DashLink.TAKEN))
        val taken = state
        assertEquals(DashLink.TAKEN, taken.dash)
        assertTrue("another app holds the port: the Wi-Fi does not change that", on(BikeNetworkAvailable).isEmpty())
        assertEquals(taken, state)

        on(Stop)
        on(ProjectionEvent.StopDone)
        assertTrue(on(BikeNetworkAvailable).isEmpty())
        assertEquals(Projection(), state)
    }

    @Test
    fun `with Android Auto down the dash waits for it instead of being started`() {
        running()
        on(DashChanged(DashLink.DROPPED))
        on(AndroidAutoChanged(AaLinkState.DROPPED))
        val effects = on(BikeNetworkAvailable)
        assertEquals(listOf(CancelDashRetry), effects)
        assertEquals(DashLink.OFF, state.dash)
    }
}
