package com.reau.app.service

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.ui.MainLook
import com.reau.app.ui.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dash's Wi-Fi never approved by the rider: while Android's dialog is expected, the headline says so. */
class ProjectionMachineFirstJoinTest {
    private fun running(dash: DashLink, firstJoin: Boolean, wasReady: Boolean = false) = Projection(
        phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = dash, dashFirstJoin = firstJoin, dashWasReady = wasReady,
    )

    @Test
    fun `asking for a network never approved says that Android may ask the rider`() {
        assertEquals(Headline.DASH_JOIN_ASK, running(DashLink.CONNECTING, firstJoin = true).headline)
    }

    @Test
    fun `between two tries of a network never approved the hint stays, with no flicker to looking`() {
        assertEquals(Headline.DASH_JOIN_ASK, running(DashLink.DROPPED, firstJoin = true).headline)
    }

    @Test
    fun `a network already approved keeps the old headlines`() {
        assertEquals(Headline.CONNECTING, running(DashLink.CONNECTING, firstJoin = false).headline)
        assertEquals(Headline.DASH_SEARCHING, running(DashLink.DROPPED, firstJoin = false).headline)
    }

    @Test
    fun `a dash lost after it was ready is lost, never a first approval`() {
        assertEquals(Headline.DASH_LOST, running(DashLink.DROPPED, firstJoin = true, wasReady = true).headline)
    }

    @Test
    fun `what the rider must act on comes before the hint`() {
        assertEquals(Headline.DASH_TAKEN, running(DashLink.TAKEN, firstJoin = true).headline)
        assertEquals(Headline.DASH_NAME_NEEDED, running(DashLink.NAME_NEEDED, firstJoin = true).headline)
        assertEquals(Headline.ANDROID_AUTO_SERVER_OFF, running(DashLink.CONNECTING, true).copy(androidAuto = AaLinkState.SERVER_OFF).headline)
        assertEquals(Headline.NETWORK_SERVER, running(DashLink.CONNECTING, true).copy(networkServer = true).headline)
    }

    @Test
    fun `the event sets and clears the flag of a running projection`() {
        val on = ProjectionMachine.step(running(DashLink.CONNECTING, false), ProjectionEvent.DashFirstJoin(true))
        assertTrue(on.state.dashFirstJoin)
        assertTrue("it only changes the state", on.effects.isEmpty())

        val off = ProjectionMachine.step(on.state, ProjectionEvent.DashFirstJoin(false))
        assertFalse(off.state.dashFirstJoin)
    }

    @Test
    fun `a dash out of reach shows the old looking-for text, with no tap Connect promise`() {
        // The link tells false while the scan does not see the dash (bike off): the dialog will not come.
        val absent = ProjectionMachine.step(running(DashLink.CONNECTING, false), ProjectionEvent.DashFirstJoin(false)).state
        assertEquals(Headline.CONNECTING, absent.headline)
        val dropped = absent.copy(dash = DashLink.DROPPED)
        assertEquals(Headline.DASH_SEARCHING, dropped.headline)
        assertEquals(R.string.sub_dash_searching, MainLook.sub(dropped))
        assertEquals(R.string.notif_title_dash_searching to R.string.notif_text_dash_searching, ProjectionNotification.contentFor(dropped.headline))
    }

    @Test
    fun `the hint shows once a later request finds the dash in the scan`() {
        val before = ProjectionMachine.step(running(DashLink.DROPPED, false), ProjectionEvent.DashFirstJoin(false)).state
        assertEquals(Headline.DASH_SEARCHING, before.headline)

        val after = ProjectionMachine.step(before, ProjectionEvent.DashFirstJoin(true)).state
        assertEquals(Headline.DASH_JOIN_ASK, after.headline)
        assertEquals(R.string.sub_dash_join_ask, MainLook.sub(after))
    }

    @Test
    fun `a stopped projection takes no first approval`() {
        val step = ProjectionMachine.step(Projection(), ProjectionEvent.DashFirstJoin(true))

        assertEquals(Projection(), step.state)
    }

    @Test
    fun `the dash being ready ends the first approval`() {
        val step = ProjectionMachine.step(running(DashLink.CONNECTING, true), ProjectionEvent.DashChanged(DashLink.READY))

        assertFalse(step.state.dashFirstJoin)
        assertEquals(Headline.STREAMING, step.state.headline)
    }

    @Test
    fun `switching off clears it with the rest`() {
        val step = ProjectionMachine.step(running(DashLink.CONNECTING, true), ProjectionEvent.Stop)

        assertFalse(step.state.dashFirstJoin)
    }

    @Test
    fun `the hint reaches the notification, the line under the headline and the headline`() {
        assertEquals(R.string.notif_title_dash_searching to R.string.notif_text_dash_join_ask, ProjectionNotification.contentFor(Headline.DASH_JOIN_ASK))
        assertEquals(R.string.sub_dash_join_ask, MainLook.sub(running(DashLink.CONNECTING, true)))
        assertEquals(R.string.headline_dash_searching, ScreenText.headline(Headline.DASH_JOIN_ASK))
    }
}
