package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.ui.components.StatusKind

/**
 * How the main screen ("Tela principal") shows the projection: each side's colour, the headline's line, the warnings
 * above the bridge and when the "Tela do Android Auto" can open. No Android in it: tested on the JVM.
 */
object MainLook {
    /** The warnings, in display order; each is shown only while its trouble lasts. */
    enum class Warning {
        SERVER_OFF, UNRESPONSIVE, CLOSING_PREVIOUS, OTHER_EDITION, NOT_CALLING, NO_VIDEO,
        BATTERY_EXEMPTION, BATTERY_SAVER, DASH_TAKEN, NAME_NEEDED, VPN, LOCATION_OFF, ALERTS_MUTED, ALERTS_BLOCKED,
    }

    fun androidAutoKind(p: Projection): StatusKind = when {
        p.phase == Phase.PREPARING -> StatusKind.CONNECTING
        p.card && p.phase == Phase.RUNNING -> StatusKind.OK
        else -> when (p.androidAuto) {
            AaLinkState.IDLE -> StatusKind.IDLE
            AaLinkState.CONNECTING, AaLinkState.DROPPED, AaLinkState.CLOSING_PREVIOUS -> StatusKind.CONNECTING
            AaLinkState.ACTIVE -> StatusKind.OK
            AaLinkState.SERVER_OFF, AaLinkState.UNRESPONSIVE, AaLinkState.NOT_CALLING -> StatusKind.ERROR
        }
    }

    fun dashKind(p: Projection): StatusKind = if (p.phase == Phase.PREPARING) {
        StatusKind.CONNECTING
    } else {
        when (p.dash) {
            DashLink.OFF -> StatusKind.IDLE
            DashLink.CONNECTING, DashLink.DROPPED -> StatusKind.CONNECTING
            DashLink.READY -> StatusKind.OK
            DashLink.TAKEN, DashLink.NAME_NEEDED -> StatusKind.ERROR
        }
    }

    /** The line under the headline; null for a start that failed, whose line is its reason. */
    fun sub(p: Projection): Int? = when (p.headline) {
        Headline.STOPPED -> R.string.sub_stopped
        Headline.STOPPING -> R.string.sub_stopping
        Headline.PROBLEM -> null
        Headline.PREPARING -> R.string.sub_preparing
        // The wallpaper mode (the card) has no Android Auto to wait for: only the dash.
        Headline.CONNECTING -> if (p.card) R.string.sub_connecting_wallpaper else R.string.sub_connecting
        Headline.STREAMING -> R.string.sub_streaming
        Headline.NETWORK_SERVER -> R.string.sub_network_server
        Headline.CARD -> R.string.sub_card
        Headline.ANDROID_AUTO_SERVER_OFF -> R.string.sub_server_off
        Headline.ANDROID_AUTO_UNRESPONSIVE -> R.string.sub_unresponsive
        Headline.ANDROID_AUTO_CLOSING_PREVIOUS -> R.string.sub_closing_previous
        Headline.ANDROID_AUTO_NOT_CALLING -> R.string.sub_not_calling
        Headline.ANDROID_AUTO_NO_VIDEO -> R.string.sub_no_video
        Headline.ANDROID_AUTO_IMAGE_STALE -> R.string.sub_image_stale
        Headline.DASH_LOST -> R.string.sub_dash_lost
        Headline.DASH_SEARCHING -> R.string.sub_dash_searching
        Headline.DASH_JOIN_ASK -> R.string.sub_dash_join_ask
        Headline.DASH_TAKEN -> R.string.sub_dash_taken
        Headline.DASH_NAME_NEEDED -> R.string.sub_name_needed
    }

    /**
     * Whether to name the old "lite" app as a suspect: it is installed ([otherEdition]) and Android Auto accepts the
     * connection and does not answer the handshake, again and again (the link says so with [AaLinkState.UNRESPONSIVE] or
     * [AaLinkState.CLOSING_PREVIOUS], three in a row). Whether it is running cannot be known, so this only says "may".
     */
    fun otherEditionSuspect(androidAuto: AaLinkState, otherEdition: Boolean): Boolean =
        otherEdition && (androidAuto == AaLinkState.UNRESPONSIVE || androidAuto == AaLinkState.CLOSING_PREVIOUS)

    /**
     * [vpn]: REau's own traffic goes into a VPN, which keeps it off the bike's Wi-Fi. [otherEdition]: the old "lite" app is installed. [locationOff]: Android's Location switch is off although REau has the permission ([LocationSwitch]).
     */
    fun warnings(
        p: Projection,
        battery: BatteryUi,
        vpn: Boolean = false,
        otherEdition: Boolean = false,
        locationOff: Boolean = false,
    ): List<Warning> = listOfNotNull(
        Warning.SERVER_OFF.takeIf { p.androidAuto == AaLinkState.SERVER_OFF },
        Warning.UNRESPONSIVE.takeIf { p.androidAuto == AaLinkState.UNRESPONSIVE },
        Warning.CLOSING_PREVIOUS.takeIf { p.androidAuto == AaLinkState.CLOSING_PREVIOUS },
        Warning.OTHER_EDITION.takeIf { otherEditionSuspect(p.androidAuto, otherEdition) },
        Warning.NOT_CALLING.takeIf { p.androidAuto == AaLinkState.NOT_CALLING },
        Warning.NO_VIDEO.takeIf { p.headline == Headline.ANDROID_AUTO_NO_VIDEO },
        Warning.BATTERY_EXEMPTION.takeIf { BatteryNotice.NOT_EXEMPT in battery.notices },
        Warning.BATTERY_SAVER.takeIf { BatteryNotice.SAVER_ON in battery.notices },
        Warning.DASH_TAKEN.takeIf { p.dash == DashLink.TAKEN },
        Warning.NAME_NEEDED.takeIf { p.dash == DashLink.NAME_NEEDED },
        Warning.VPN.takeIf { vpn },
        Warning.LOCATION_OFF.takeIf { locationOff },
    )

    /** The card for the alert channel when it would not be heard ([AlertChannelState]: muted, or blocked in Android); after the others, nothing when loud. */
    fun alertWarnings(level: com.reau.app.service.AlertChannelState.Level): List<Warning> = listOfNotNull(
        Warning.ALERTS_MUTED.takeIf { level == com.reau.app.service.AlertChannelState.Level.MUTED },
        Warning.ALERTS_BLOCKED.takeIf { level == com.reau.app.service.AlertChannelState.Level.BLOCKED },
    )

    /**
     * Why the "Tela do Android Auto" cannot open now; null when it can: with the projection on, outside the card mode,
     * also without the dash (to fit the picture at home).
     */
    fun screenUnavailable(p: Projection): Int? = when {
        p.phase == Phase.STOPPED -> R.string.reason_stopped
        p.phase == Phase.PREPARING -> R.string.reason_waiting
        p.card -> R.string.reason_card
        else -> null
    }
}
