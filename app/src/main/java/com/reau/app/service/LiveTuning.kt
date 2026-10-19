package com.reau.app.service

import com.reau.app.settings.REauSettings

/**
 * What a running projection does when the rider changes a setting: every video setting
 * applies at once, with no switching off and on.
 */
object LiveTuning {
    enum class Step {
        /** The picture's place on the dash (fit, crop, move): the compositor takes it at its next frame. */
        REFIT,

        /**
         * What Android Auto was told when its session began (density, margins): that session is ended and
         * the next, dialled at once, is told the new values; the dash stays connected and keeps the last picture. The
         * fit goes with the new session, since the margins move the picture Android Auto draws.
         */
        NEW_ANDROID_AUTO_SESSION,

        /**
         * The stream's own settings (frame rate ceiling, video clock, the bitrate, its mode and the
         * keyframe): a new stream to the dash with them.
         */
        NEW_STREAM,

        /** The map theme and "Usar a localização" (the place for the sun): day or night asked again now instead of at the next minute. */
        NIGHT_NOW,

        /** The joystick page, taken at the next tap. */
        JOYSTICK,

        /** The stats text on the dash's video: on or off, its place and size, taken at the next frame. */
        STATS_OVERLAY,

        /** The web server's two switches: the server up or down and the touch allowed or not, at once. */
        WEB,

        /**
         * What the dash shows besides the picture, read from the live settings at each beat or event: the phone's state, the album art,
         * the route's extras and the weather. Nothing is redone: the service only has to be told (it takes the settings), and the
         * weather, when it is switched on, is fetched at once instead of at the next minute.
         */
        DASH_EXTRAS,
    }

    /**
     * What the new Android Auto session is told ([Step.NEW_ANDROID_AUTO_SESSION]): the running configuration with the density, the margins
     * and whether REau takes the sound (the web server's sinks) of [settings] as they are now. Leaving the sinks out of this was a defect
     * of the first try: the session came back as it was, with no audio channel, and the sound never reached the browser.
     */
    fun sessionConfig(running: com.reau.app.aa.AaConfig, settings: REauSettings): com.reau.app.aa.AaConfig = running.copy(
        density = settings.density,
        margins = com.reau.app.video.AaMargins(settings.marginW, settings.marginH),
        audio = settings.audioSinks,
    )

    fun steps(before: REauSettings, after: REauSettings): Set<Step> = buildSet {
        // What Android Auto was told when its session began: the density, the margins and whether REau takes its sound.
        val session = before.density != after.density ||
            before.marginW != after.marginW || before.marginH != after.marginH ||
            before.audioSinks != after.audioSinks
        if (session) add(Step.NEW_ANDROID_AUTO_SESSION) else if (before.refitNeeded(after)) add(Step.REFIT)
        if (before.stream != after.stream) add(Step.NEW_STREAM)
        if (before.mapTheme != after.mapTheme || before.useLocation != after.useLocation) add(Step.NIGHT_NOW)
        if (before.joystick != after.joystick) add(Step.JOYSTICK)
        if (before.overlay != after.overlay) add(Step.STATS_OVERLAY)
        if (webChanged(before, after)) add(Step.WEB)
        if (dashExtrasChanged(before, after)) add(Step.DASH_EXTRAS)
    }

    private fun webChanged(before: REauSettings, after: REauSettings) =
        before.webView != after.webView || before.webTouch != after.webTouch || before.webAudio != after.webAudio

    private fun dashExtrasChanged(before: REauSettings, after: REauSettings) =
        before.dashPhoneStatus != after.dashPhoneStatus || before.dashAlbumArt != after.dashAlbumArt ||
            before.dashRouteExtras != after.dashRouteExtras || before.dashWeather != after.dashWeather

    /** What a stream to the dash is made with. */
    private val REauSettings.stream get() = listOf(fpsCeiling, clockSlowPercent, streamBitrateBps, bitrateMode, keyframeSec)
}
