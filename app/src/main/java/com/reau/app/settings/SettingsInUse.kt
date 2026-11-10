package com.reau.app.settings

import com.reau.app.dash.DashConfig

/**
 * The ride log said what the rider changed (`setting …: a -> b`) but not what was in use, so a log
 * could not say whether the info ring or the detailed log were on (it had to be
 * deduced from other lines). [line] is one line of the settings that change what a ride does, written when the app starts
 * and with each projection.
 *
 * Only a list of names allowed ([LOGGED]) is written, and only booleans, numbers and enum names: nothing with free text, a
 * path or a place. A setting that is not in either list fails SettingsInUseTest, so a new field has to be decided on.
 */
object SettingsInUse {
    /** Written, by the field's name in [REauSettings]. */
    internal val LOGGED = setOf(
        "density", "marginW", "marginH", "fit", "cropPercent",
        "fpsCeiling", "streamBitrateBps", "bitrateMode", "keyframeSec", "clockSlowPercent",
        "recordVideo", "recordSessions", "cardImage", "mapTheme",
        "maneuverData", "maneuverFpsProfile", "speedFpsProfile", "navProfileMode",
        "autoJoinWifi", "detailedLog", "rideLog", "useLocation", "logKeepDays", "noDashStopMinutes",
        "answerCalls", "phoneTouch", "dashMusic", "dashCaller", "gemini",
        "aaNewProtocol", "clearAaFocus", "dashMask",
        "statsOverlay", "buttonOverlay", "dashPhoneStatus", "dashAlbumArt", "dashWeather", "infoRing", "infoRingData", "infoRingCarousel", "infoRingSeconds",
        "infoRingInPlace",
        "maneuverRing", "webView", "webTouch", "webAudio",
        "experimentRtpPacing", "experimentDebugDscp", "experimentAdaptiveBitrate", "experimentWifiBind",
    )

    /** Left out on purpose: the fine placement of the picture and the numbers on it (said when they change), and the joystick's text. */
    internal val NOT_LOGGED = setOf(
        "cropX", "cropY", "moveX", "moveY", "menuFit", "menuFitKept", "navFit", "zoom", "cover", "statsX", "statsY", "statsSize",
        "buttonX", "buttonY", "buttonSize", "joystick",
    )

    fun line(settings: REauSettings): String {
        val fields = settings.fieldsOf(settings.toString())
        val said = fields.filterKeys { it in LOGGED }.entries.joinToString(" ") { (name, value) ->
            "$name=${if (name == "cardImage") card(value) else value}"
        }
        return "settings in use: $said"
    }

    /**
     * One line for the connection experiments that are on, or null when none is: the ride log of a ride made with one says it in plain words
     * (the settings line above carries them too, as `experimentX=true`).
     */
    fun experimentsLine(settings: REauSettings): String? {
        val on = listOfNotNull(
            "video pacing (RTP packets spaced out)".takeIf { settings.experimentRtpPacing },
            "priority marks on a debug build".takeIf { settings.experimentDebugDscp },
            "lower adaptive bitrate (${DashConfig.EXPERIMENT_TARGET_BPS / 1000} kbps target)".takeIf { settings.experimentAdaptiveBitrate },
            "Wi-Fi low-latency lock and bind retries on the same network".takeIf { settings.experimentWifiBind },
        )
        return if (on.isEmpty()) null else "connection experiments ON: ${on.joinToString("; ")}"
    }

    /** The picture sent while no map is there: which one is not said (it is a file of the rider's), only whether it is the built-in one. */
    private fun card(value: String) = when (value) {
        "null" -> "none"
        REauSettings.DEFAULT_CARD -> "default"
        else -> "picked"
    }
}
