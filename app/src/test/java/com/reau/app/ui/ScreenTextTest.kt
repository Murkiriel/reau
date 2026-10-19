package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.video.FrameFit
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the main screen says for each state, as string resources (the texts themselves are in strings.xml). */
class ScreenTextTest {

    @Test
    fun `each headline has its own text`() {
        val expected = mapOf(
            Headline.STOPPED to R.string.status_stopped,
            Headline.STOPPING to R.string.headline_stopping,
            Headline.PROBLEM to R.string.headline_problem,
            Headline.PREPARING to R.string.headline_preparing,
            Headline.CONNECTING to R.string.headline_connecting,
            Headline.ANDROID_AUTO_SERVER_OFF to R.string.headline_server_off,
            Headline.ANDROID_AUTO_UNRESPONSIVE to R.string.headline_android_auto_unresponsive,
            Headline.ANDROID_AUTO_CLOSING_PREVIOUS to R.string.headline_android_auto_closing_previous,
            Headline.ANDROID_AUTO_NOT_CALLING to R.string.headline_android_auto_not_calling,
            Headline.ANDROID_AUTO_NO_VIDEO to R.string.headline_android_auto_no_video,
            Headline.ANDROID_AUTO_IMAGE_STALE to R.string.headline_android_auto_image_stale,
            Headline.DASH_LOST to R.string.headline_dash_lost,
            Headline.DASH_SEARCHING to R.string.headline_dash_searching,
            Headline.DASH_JOIN_ASK to R.string.headline_dash_searching,
            Headline.STREAMING to R.string.headline_streaming,
            Headline.CARD to R.string.headline_card,
            Headline.DASH_TAKEN to R.string.headline_dash_taken,
            Headline.DASH_NAME_NEEDED to R.string.headline_dash_name_needed,
            Headline.NETWORK_SERVER to R.string.headline_network_server,
        )
        assertEquals(Headline.entries.toSet(), expected.keys)
        for ((headline, text) in expected) assertEquals(headline.name, text, ScreenText.headline(headline))
    }

    @Test
    fun `the join-ask hint changes the line under the headline, never the headline itself`() {
        // Searching with the dash out of reach and searching with Android's dialog expected read the same on top.
        assertEquals(ScreenText.headline(Headline.DASH_SEARCHING), ScreenText.headline(Headline.DASH_JOIN_ASK))
    }

    @Test
    fun `each state of the Android Auto side`() {
        assertEquals(R.string.link_off, ScreenText.androidAuto(AaLinkState.IDLE))
        assertEquals(R.string.link_server_off, ScreenText.androidAuto(AaLinkState.SERVER_OFF))
        assertEquals(R.string.link_connecting, ScreenText.androidAuto(AaLinkState.CONNECTING))
        assertEquals(R.string.link_active, ScreenText.androidAuto(AaLinkState.ACTIVE))
        assertEquals(R.string.link_dropped_android_auto, ScreenText.androidAuto(AaLinkState.DROPPED))
        assertEquals(R.string.link_unresponsive, ScreenText.androidAuto(AaLinkState.UNRESPONSIVE))
        assertEquals(R.string.link_closing_previous, ScreenText.androidAuto(AaLinkState.CLOSING_PREVIOUS))
        assertEquals(R.string.link_not_calling, ScreenText.androidAuto(AaLinkState.NOT_CALLING))
    }

    @Test
    fun `each problem of a fit has its own text`() {
        val expected = mapOf(
            FrameFit.Problem.SOURCE_OUTSIDE_FRAME to R.string.problem_source_outside,
            FrameFit.Problem.DEST_OUTSIDE_FRAME to R.string.problem_dest_outside,
            FrameFit.Problem.TOO_SMALL to R.string.problem_too_small,
            FrameFit.Problem.DISTORTED to R.string.problem_distorted,
            FrameFit.Problem.NOT_VISIBLE to R.string.problem_not_visible,
        )
        assertEquals(FrameFit.Problem.entries.toSet(), expected.keys)
        for ((problem, text) in expected) assertEquals(problem.name, text, ScreenText.problem(problem))
    }

    @Test
    fun `each state of the dash side`() {
        assertEquals(R.string.link_off, ScreenText.dash(DashLink.OFF))
        assertEquals(R.string.link_connecting, ScreenText.dash(DashLink.CONNECTING))
        assertEquals(R.string.link_ready, ScreenText.dash(DashLink.READY))
        assertEquals(R.string.link_dropped_dash, ScreenText.dash(DashLink.DROPPED))
    }

    @Test
    fun `a dash that never connected says it is being looked for, not dropped`() {
        val failed = Projection(phase = Phase.RUNNING, dash = DashLink.DROPPED)
        assertEquals(R.string.link_searching_dash, ScreenText.dash(failed))
        assertEquals(R.string.link_dropped_dash, ScreenText.dash(failed.copy(dashWasReady = true)))
        assertEquals(R.string.link_ready, ScreenText.dash(failed.copy(dash = DashLink.READY)))
        assertEquals(R.string.link_connecting, ScreenText.dash(failed.copy(dash = DashLink.CONNECTING)))
    }

    @Test
    fun `a dash held by another app is said so`() {
        assertEquals(R.string.link_taken, ScreenText.dash(DashLink.TAKEN))
    }

    @Test
    fun `a dash whose full name is not known is said so`() {
        assertEquals(R.string.link_name_needed, ScreenText.dash(DashLink.NAME_NEEDED))
    }
}
