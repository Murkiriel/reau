package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.ServerResult
import com.reau.app.service.AaServerStarter
import com.reau.app.service.DashLink
import com.reau.app.service.Headline
import com.reau.app.service.Projection
import com.reau.app.video.FrameFit

/** What the main screen says for each state, as string resources. */
object ScreenText {
    fun headline(headline: Headline): Int = when (headline) {
        Headline.STOPPED -> R.string.status_stopped
        Headline.STOPPING -> R.string.headline_stopping
        Headline.PROBLEM -> R.string.headline_problem
        Headline.PREPARING -> R.string.headline_preparing
        Headline.CONNECTING -> R.string.headline_connecting
        Headline.ANDROID_AUTO_SERVER_OFF -> R.string.headline_server_off
        Headline.ANDROID_AUTO_UNRESPONSIVE -> R.string.headline_android_auto_unresponsive
        Headline.ANDROID_AUTO_CLOSING_PREVIOUS -> R.string.headline_android_auto_closing_previous
        Headline.ANDROID_AUTO_NOT_CALLING -> R.string.headline_android_auto_not_calling
        Headline.ANDROID_AUTO_NO_VIDEO -> R.string.headline_android_auto_no_video
        Headline.ANDROID_AUTO_IMAGE_STALE -> R.string.headline_android_auto_image_stale
        Headline.DASH_LOST -> R.string.headline_dash_lost
        Headline.DASH_SEARCHING, Headline.DASH_JOIN_ASK -> R.string.headline_dash_searching
        Headline.STREAMING -> R.string.headline_streaming
        Headline.NETWORK_SERVER -> R.string.headline_network_server
        Headline.CARD -> R.string.headline_card
        Headline.DASH_TAKEN -> R.string.headline_dash_taken
        Headline.DASH_NAME_NEEDED -> R.string.headline_dash_name_needed
    }

    fun androidAuto(state: AaLinkState): Int = when (state) {
        AaLinkState.IDLE -> R.string.link_off
        AaLinkState.SERVER_OFF -> R.string.link_server_off
        AaLinkState.CONNECTING -> R.string.link_connecting
        AaLinkState.ACTIVE -> R.string.link_active
        AaLinkState.DROPPED -> R.string.link_dropped_android_auto
        AaLinkState.UNRESPONSIVE -> R.string.link_unresponsive
        AaLinkState.CLOSING_PREVIOUS -> R.string.link_closing_previous
        AaLinkState.NOT_CALLING -> R.string.link_not_calling
    }

    fun problem(problem: FrameFit.Problem): Int = when (problem) {
        FrameFit.Problem.SOURCE_OUTSIDE_FRAME -> R.string.problem_source_outside
        FrameFit.Problem.DEST_OUTSIDE_FRAME -> R.string.problem_dest_outside
        FrameFit.Problem.TOO_SMALL -> R.string.problem_too_small
        FrameFit.Problem.DISTORTED -> R.string.problem_distorted
        FrameFit.Problem.NOT_VISIBLE -> R.string.problem_not_visible
    }

    /** The cover over Android Auto's menu while a walk goes on: switching the server on or off. */
    fun cover(mode: com.reau.app.aa.ServerMenuRun.Mode): Int =
        if (mode == com.reau.app.aa.ServerMenuRun.Mode.STOP) R.string.aa_server_cover_stopping else R.string.aa_server_cover

    /** How the last try with Android Auto's server went; null says nothing. */
    @Suppress("CyclomaticComplexMethod") // a plain lookup, one branch per result
    fun server(result: ServerResult): Int? = when (result) {
        ServerResult.NONE -> null
        ServerResult.WORKING -> R.string.aa_server_working
        ServerResult.STOPPING -> R.string.aa_server_stopping
        ServerResult.STARTED -> R.string.aa_server_started
        ServerResult.SERVICE_OFF -> R.string.aa_server_service_off
        ServerResult.LOCKED -> R.string.aa_server_locked
        ServerResult.NOT_INSTALLED -> R.string.aa_server_not_installed
        ServerResult.SETTINGS_DID_NOT_OPEN -> R.string.aa_server_settings_did_not_open
        ServerResult.UNEXPECTED_SCREEN -> R.string.aa_server_unexpected_screen
        ServerResult.MENU_DID_NOT_OPEN -> R.string.aa_server_menu_did_not_open
        ServerResult.DEVELOPER_MODE_OFF -> R.string.aa_server_developer_mode_off
        ServerResult.UNKNOWN_MENU -> R.string.aa_server_unknown_menu
        ServerResult.ALREADY_RUNNING -> R.string.aa_server_already_running
        ServerResult.NOT_UP -> R.string.aa_server_not_up
        ServerResult.INTERRUPTED -> R.string.aa_server_interrupted
        ServerResult.STOPPED -> R.string.aa_server_stopped
        ServerResult.LEFT_ON -> R.string.aa_server_left_on
    }

    /** Where REau's accessibility service stands. */
    fun accessibility(state: AaServerStarter.Accessibility): Int = when (state) {
        AaServerStarter.Accessibility.CONNECTED -> R.string.aa_server_accessibility_connected
        AaServerStarter.Accessibility.ENABLED -> R.string.aa_server_accessibility_enabled
        AaServerStarter.Accessibility.OFF -> R.string.aa_server_accessibility_off
    }

    /** The dash side of the bridge: a link that failed before any connection existed says "looking", not "dropped". */
    fun dash(p: Projection): Int =
        if (p.dash == DashLink.DROPPED && !p.dashWasReady) R.string.link_searching_dash else dash(p.dash)

    fun dash(link: DashLink): Int = when (link) {
        DashLink.OFF -> R.string.link_off
        DashLink.CONNECTING -> R.string.link_connecting
        DashLink.READY -> R.string.link_ready
        DashLink.DROPPED -> R.string.link_dropped_dash
        DashLink.TAKEN -> R.string.link_taken
        DashLink.NAME_NEEDED -> R.string.link_name_needed
    }
}
