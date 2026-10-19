package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEffect.StopDash
import com.reau.app.service.ProjectionEffect.StopVideo
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.NetworkServer
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With the network server (the browser's, "Ver no navegador") on, REau does not look for the dash,
 * because the point of that mode is to run Android Auto on something that is not the dash (a computer, an old multimedia with a
 * browser). Switched on while the projection runs, it lets the dash go; switched off, the dash is looked for again.
 */
class ProjectionMachineNetworkServerTest {
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

    private fun streaming() {
        running()
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
    }

    @Test
    fun `a projection begins looking for the dash, with the network server off`() {
        running()
        assertFalse(state.networkServer)
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE)))
    }

    @Test
    fun `switched on with the dash streaming, the video stops and the dash is let go`() {
        streaming()
        assertEquals(listOf(StopVideo, StopDash), on(NetworkServer(true)))
        assertTrue(state.networkServer)
        assertEquals(DashLink.OFF, state.dash)
        assertEquals(0, state.dashRetries)
        assertFalse(state.streaming)
    }

    @Test
    fun `switched on while the dash is being reached or lost, the try goes and no video stops`() {
        running()
        on(AndroidAutoChanged(AaLinkState.ACTIVE)) // the dash is connecting
        assertEquals(listOf(StopDash), on(NetworkServer(true)))
        on(DashChanged(DashLink.DROPPED))
        assertEquals(DashLink.OFF, state.dash)
    }

    @Test
    fun `with it on, the dash is not looked for again, whatever Android Auto does`() {
        running()
        on(NetworkServer(true))
        assertTrue(on(AndroidAutoChanged(AaLinkState.ACTIVE)).isEmpty())
        assertTrue(on(AndroidAutoChanged(AaLinkState.DROPPED)).isEmpty())
        assertTrue(on(AndroidAutoChanged(AaLinkState.ACTIVE)).isEmpty())
        assertEquals(DashLink.OFF, state.dash)
    }

    @Test
    fun `with it on, what the let-go dash session still reports changes nothing and starts no retry`() {
        streaming()
        on(NetworkServer(true))
        // The session says idle (read as a drop) and an error right after it goes: neither may schedule a retry.
        assertTrue(on(DashChanged(DashLink.OFF)).isEmpty())
        assertTrue(on(DashChanged(DashLink.DROPPED)).none { it is RetryDashIn })
        assertEquals(DashLink.OFF, state.dash)
    }

    @Test
    fun `a retry already waiting when it was switched on does nothing when it comes`() {
        running()
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertTrue(on(DashChanged(DashLink.DROPPED)).any { it is RetryDashIn })
        on(NetworkServer(true))
        assertTrue(on(DashRetryDue).isEmpty())
        assertEquals(DashLink.OFF, state.dash)
    }

    @Test
    fun `switched off with Android Auto up, the dash is looked for again at once`() {
        streaming()
        on(NetworkServer(true))
        assertEquals(listOf(StartDash), on(NetworkServer(false)))
        assertFalse(state.networkServer)
        assertEquals(DashLink.CONNECTING, state.dash)
    }

    @Test
    fun `switched off with Android Auto not up, the dash waits for it as at the start`() {
        running()
        on(NetworkServer(true))
        assertTrue(on(NetworkServer(false)).isEmpty())
        assertEquals(DashLink.OFF, state.dash)
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE)))
    }

    @Test
    fun `saying the same thing twice does nothing the second time`() {
        streaming()
        on(NetworkServer(true))
        assertTrue(on(NetworkServer(true)).isEmpty())
        on(NetworkServer(false))
        assertTrue(on(NetworkServer(false)).isEmpty())
    }

    @Test
    fun `while the service is preparing it is kept, so the dash is not looked for after`() {
        on(Start)
        assertTrue(on(NetworkServer(true)).isEmpty())
        on(Prepared)
        assertTrue(on(AndroidAutoChanged(AaLinkState.ACTIVE)).isEmpty())
        assertEquals(DashLink.OFF, state.dash)
    }

    @Test
    fun `stopped, it says nothing and a new projection starts with it off`() {
        assertTrue(on(NetworkServer(true)).isEmpty())
        assertFalse(state.networkServer)
        streaming()
        on(NetworkServer(true))
        on(Stop)
        on(ProjectionEvent.StopDone)
        assertFalse(state.networkServer)
        running()
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE)))
    }

    @Test
    fun `the screen says the network server is serving, unless Android Auto itself has a problem to fix`() {
        running()
        on(NetworkServer(true))
        assertEquals(Headline.NETWORK_SERVER, state.headline)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals(Headline.NETWORK_SERVER, state.headline)
        on(AndroidAutoChanged(AaLinkState.SERVER_OFF))
        assertEquals("the Android Auto problem is the rider's to fix first", Headline.ANDROID_AUTO_SERVER_OFF, state.headline)
        on(AndroidAutoChanged(AaLinkState.UNRESPONSIVE))
        assertEquals(Headline.ANDROID_AUTO_UNRESPONSIVE, state.headline)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals(Headline.NETWORK_SERVER, state.headline)
    }

    @Test
    fun `the card mode has no network server, so it does not let the dash go`() {
        on(ProjectionEvent.StartCard)
        on(Prepared)
        assertTrue(on(NetworkServer(true)).isEmpty())
        assertFalse(state.networkServer)
        assertEquals(DashLink.CONNECTING, state.dash)
    }
}
