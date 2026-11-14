package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.ui.MainLook.Warning
import com.reau.app.ui.components.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The main screen: how it shows each state. */
class MainLookTest {
    private fun running(aa: AaLinkState = AaLinkState.CONNECTING, dash: DashLink = DashLink.CONNECTING, card: Boolean = false) =
        Projection(phase = Phase.RUNNING, androidAuto = aa, dash = dash, card = card)

    @Test
    fun `each Android Auto state takes its colour`() {
        val expected = mapOf(
            AaLinkState.IDLE to StatusKind.IDLE,
            AaLinkState.CONNECTING to StatusKind.CONNECTING,
            AaLinkState.DROPPED to StatusKind.CONNECTING,
            AaLinkState.ACTIVE to StatusKind.OK,
            AaLinkState.SERVER_OFF to StatusKind.ERROR,
            AaLinkState.UNRESPONSIVE to StatusKind.ERROR,
            AaLinkState.CLOSING_PREVIOUS to StatusKind.CONNECTING,
            AaLinkState.NOT_CALLING to StatusKind.ERROR,
        )
        assertEquals(AaLinkState.entries.toSet(), expected.keys)
        for ((state, kind) in expected) assertEquals(state.name, kind, MainLook.androidAutoKind(running(aa = state)))
        assertEquals(StatusKind.IDLE, MainLook.androidAutoKind(Projection()))
    }

    @Test
    fun `each dash state takes its colour`() {
        val expected = mapOf(
            DashLink.OFF to StatusKind.IDLE,
            DashLink.CONNECTING to StatusKind.CONNECTING,
            DashLink.DROPPED to StatusKind.CONNECTING,
            DashLink.READY to StatusKind.OK,
            DashLink.TAKEN to StatusKind.ERROR,
            DashLink.NAME_NEEDED to StatusKind.ERROR,
        )
        assertEquals(DashLink.entries.toSet(), expected.keys)
        for ((link, kind) in expected) assertEquals(link.name, kind, MainLook.dashKind(running(dash = link)))
    }

    @Test
    fun `preparing, both sides are connecting`() {
        val preparing = Projection(phase = Phase.PREPARING)
        assertEquals(StatusKind.CONNECTING, MainLook.androidAutoKind(preparing))
        assertEquals(StatusKind.CONNECTING, MainLook.dashKind(preparing))
    }

    @Test
    fun `the card in place of Android Auto is ready once running`() {
        assertEquals(StatusKind.OK, MainLook.androidAutoKind(running(aa = AaLinkState.IDLE, card = true)))
    }

    @Test
    fun `each headline has its line under it`() {
        assertEquals(R.string.sub_stopped, MainLook.sub(Projection()))
        assertEquals(R.string.sub_stopping, MainLook.sub(Projection(stopping = true)))
        assertNull("a failed start says its reason instead", MainLook.sub(Projection(problem = "SecurityException")))
        assertEquals(R.string.sub_preparing, MainLook.sub(Projection(phase = Phase.PREPARING)))
        assertEquals(R.string.sub_connecting, MainLook.sub(running()))
        assertEquals("the wallpaper mode waits for the dash alone", R.string.sub_connecting_wallpaper, MainLook.sub(running().copy(card = true)))
        assertEquals(R.string.sub_streaming, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.READY)))
        assertEquals(R.string.sub_card, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.READY, card = true)))
        assertEquals(R.string.sub_server_off, MainLook.sub(running(aa = AaLinkState.SERVER_OFF)))
        assertEquals(R.string.sub_unresponsive, MainLook.sub(running(aa = AaLinkState.UNRESPONSIVE)))
        assertEquals(R.string.sub_closing_previous, MainLook.sub(running(aa = AaLinkState.CLOSING_PREVIOUS)))
        assertEquals(R.string.sub_dash_searching, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.DROPPED)))
        assertEquals(R.string.sub_dash_join_ask, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.DROPPED).copy(dashFirstJoin = true)))
        // Dash not seen in the scan (flag off): no "tap Connect" promise, the old line.
        assertEquals(R.string.sub_dash_searching, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.DROPPED).copy(dashFirstJoin = false)))
        assertEquals(R.string.sub_dash_lost, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.DROPPED).copy(dashWasReady = true)))
        assertEquals(R.string.sub_dash_taken, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.TAKEN)))
        assertEquals(R.string.sub_name_needed, MainLook.sub(running(AaLinkState.ACTIVE, DashLink.NAME_NEEDED)))
    }

    @Test
    fun `the warnings show while their trouble lasts, in their set order`() {
        val fine = BatteryUi(saverOn = false, exempt = true)
        assertEquals(emptyList<Warning>(), MainLook.warnings(running(AaLinkState.ACTIVE, DashLink.READY), fine))
        assertEquals(listOf(Warning.SERVER_OFF), MainLook.warnings(running(aa = AaLinkState.SERVER_OFF), fine))
        assertEquals(listOf(Warning.UNRESPONSIVE), MainLook.warnings(running(aa = AaLinkState.UNRESPONSIVE), fine))
        assertEquals(listOf(Warning.CLOSING_PREVIOUS), MainLook.warnings(running(aa = AaLinkState.CLOSING_PREVIOUS), fine))
        assertEquals(listOf(Warning.DASH_TAKEN), MainLook.warnings(running(dash = DashLink.TAKEN), fine))
        assertEquals(listOf(Warning.NAME_NEEDED), MainLook.warnings(running(dash = DashLink.NAME_NEEDED), fine))
        assertEquals(
            listOf(Warning.SERVER_OFF, Warning.BATTERY_EXEMPTION, Warning.BATTERY_SAVER, Warning.DASH_TAKEN),
            MainLook.warnings(running(AaLinkState.SERVER_OFF, DashLink.TAKEN), BatteryUi(saverOn = true, exempt = false)),
        )
        assertEquals(listOf(Warning.BATTERY_SAVER), MainLook.warnings(Projection(), BatteryUi(saverOn = true, exempt = true)))
    }

    @Test
    fun `the Android Auto screen opens with the projection on, outside the card mode`() {
        // Also without the dash, to fit the picture at home.
        assertNull(MainLook.screenUnavailable(running()))
        assertNull(MainLook.screenUnavailable(running(AaLinkState.ACTIVE, DashLink.READY)))
        assertEquals(R.string.reason_stopped, MainLook.screenUnavailable(Projection()))
        assertEquals(R.string.reason_waiting, MainLook.screenUnavailable(Projection(phase = Phase.PREPARING)))
        assertEquals(R.string.reason_card, MainLook.screenUnavailable(running(AaLinkState.ACTIVE, DashLink.READY, card = true)))
    }

    /**
     * Observed on a device: a VPN held REau, and Android refused it the bike's Wi-Fi (EPERM) with
     * nothing on the screen but the dash "lost". With REau's traffic in a VPN, the main screen says so, last.
     */
    @Test
    fun `Location switched off with the permission given is said, last`() {
        val fine = BatteryUi(saverOn = false, exempt = true)
        assertEquals(listOf(Warning.LOCATION_OFF), MainLook.warnings(Projection(), fine, locationOff = true))
        assertEquals(listOf(Warning.VPN, Warning.LOCATION_OFF), MainLook.warnings(Projection(), fine, vpn = true, locationOff = true))
        assertEquals(emptyList<Warning>(), MainLook.warnings(Projection(), fine, locationOff = false))
    }

    @Test
    fun `a VPN holding REau is said, after the other warnings`() {
        val fine = BatteryUi(saverOn = false, exempt = true)
        assertEquals(listOf(Warning.VPN), MainLook.warnings(Projection(), fine, vpn = true))
        assertEquals(listOf(Warning.BATTERY_SAVER, Warning.VPN), MainLook.warnings(Projection(), BatteryUi(saverOn = true, exempt = true), vpn = true))
        assertEquals(emptyList<Warning>(), MainLook.warnings(Projection(), fine, vpn = false))
    }

    /**
     * Seen 18/10 with both editions installed and on: the second one's Android Auto "did not respond" and nothing said why.
     * The old lite app is named only when it is installed AND Android Auto accepts and does not answer, again and again.
     */
    @Test
    fun `the line under a mute server's headline says the menu by hand, and no other state changes with it`() {
        assertEquals(R.string.sub_unresponsive, MainLook.sub(running(aa = AaLinkState.UNRESPONSIVE)))
        assertEquals(R.string.sub_closing_previous, MainLook.sub(running(aa = AaLinkState.CLOSING_PREVIOUS)))
        assertEquals(R.string.sub_server_off, MainLook.sub(running(aa = AaLinkState.SERVER_OFF)))
    }

    @Test
    fun `the other edition is named only when it is installed and Android Auto does not answer`() {
        val fine = BatteryUi(saverOn = false, exempt = true)
        for (state in AaLinkState.entries) {
            val suspect = state == AaLinkState.UNRESPONSIVE || state == AaLinkState.CLOSING_PREVIOUS
            assertEquals(state.name, suspect, MainLook.otherEditionSuspect(state, otherEdition = true))
            assertEquals("${state.name} without the other edition", false, MainLook.otherEditionSuspect(state, otherEdition = false))
            assertEquals(
                state.name,
                suspect,
                Warning.OTHER_EDITION in MainLook.warnings(running(aa = state), fine, otherEdition = true),
            )
            assertEquals(state.name, false, Warning.OTHER_EDITION in MainLook.warnings(running(aa = state), fine))
        }
        assertEquals(
            listOf(Warning.UNRESPONSIVE, Warning.OTHER_EDITION),
            MainLook.warnings(running(aa = AaLinkState.UNRESPONSIVE), fine, otherEdition = true),
        )
        assertEquals(
            listOf(Warning.CLOSING_PREVIOUS, Warning.OTHER_EDITION),
            MainLook.warnings(running(aa = AaLinkState.CLOSING_PREVIOUS), fine, otherEdition = true),
        )
        assertEquals(emptyList<Warning>(), MainLook.warnings(Projection(), fine, otherEdition = true))
    }
}
