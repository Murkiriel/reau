package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEvent.AndroidAutoVideo
import com.reau.app.ui.MainLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto's session open with no picture: the dash is black, so the screen and the notification say so instead of
 * "Transmitindo", and nothing else changes (the dash stays up, no side is stopped).
 */
class ProjectionMachineNoVideoTest {
    private val streaming = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY)

    @Test
    fun `no picture from Android Auto says so in place of streaming, and the picture coming back clears it`() {
        val silent = ProjectionMachine.step(streaming, AndroidAutoVideo(silent = true))
        assertEquals(Headline.ANDROID_AUTO_NO_VIDEO, silent.state.headline)
        assertTrue("nothing stopped or started", silent.effects.isEmpty())
        assertEquals(DashLink.READY, silent.state.dash)
        val back = ProjectionMachine.step(silent.state, AndroidAutoVideo(silent = false))
        assertEquals(Headline.STREAMING, back.state.headline)
    }

    @Test
    fun `the main screen shows the no-picture warning with it`() {
        val silent = ProjectionMachine.step(streaming, AndroidAutoVideo(silent = true)).state
        assertTrue(MainLook.Warning.NO_VIDEO in MainLook.warnings(silent, com.reau.app.ui.BatteryUi()))
        assertTrue(MainLook.Warning.NO_VIDEO !in MainLook.warnings(streaming, com.reau.app.ui.BatteryUi()))
    }

    @Test
    fun `without a dash Android Auto sends nothing by itself, so the silence is no alarm and the headline stays the dash search`() {
        // Seen in the log: session open, units=0, bike off, "scan lookup ... not found".
        for (dash in listOf(DashLink.OFF, DashLink.CONNECTING, DashLink.DROPPED)) {
            val waiting = streaming.copy(dash = dash, aaNoVideo = true)
            assertTrue("no alarm with the dash $dash", !waiting.noVideoAlarm)
            assertTrue(waiting.headline != Headline.ANDROID_AUTO_NO_VIDEO)
            assertTrue(MainLook.Warning.NO_VIDEO !in MainLook.warnings(waiting, com.reau.app.ui.BatteryUi()))
        }
        assertEquals(Headline.CONNECTING, streaming.copy(dash = DashLink.CONNECTING, aaNoVideo = true).headline)
        assertEquals(Headline.DASH_SEARCHING, streaming.copy(dash = DashLink.DROPPED, aaNoVideo = true).headline)
        // The same flag with the dash up is the stuck encoder: the alarm stays.
        assertTrue(streaming.copy(aaNoVideo = true).noVideoAlarm)
    }

    @Test
    fun `the network server keeps the alarm, since the browser is who misses the picture`() {
        val browser = streaming.copy(dash = DashLink.OFF, networkServer = true, aaNoVideo = true)
        assertEquals(Headline.ANDROID_AUTO_NO_VIDEO, browser.headline)
    }

    @Test
    fun `the card mode has no Android Auto picture to miss, and a stopped projection takes no notice`() {
        val card = ProjectionMachine.step(streaming.copy(card = true), AndroidAutoVideo(silent = true)).state
        assertEquals(Headline.CARD, card.headline)
        val stopped = ProjectionMachine.step(Projection(), AndroidAutoVideo(silent = true)).state
        assertEquals(Headline.STOPPED, stopped.headline)
    }
}
