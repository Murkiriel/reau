package com.reau.app.stream

/**
 * A faster pace for the screens of Android Auto that are not the navigation's: its app menu and the other apps (the navigation follows the profile), screens that are mostly still (about 9 new pictures a
 * second against about 30 on the map, and 156 kbps against 234 at 4 fps in the logs of 19 and 20/10/2026), where the wait for the
 * compositor's next tick (125 ms at 4 fps) is most of what the rider feels after a joystick touch. Only the pace changes: the encoder is
 * built once for the full rate and is never replaced for this, as with the REDUCED tier the other way round.
 *
 * The encoder places a keyframe every `encoderFps × keyframeSec` frames, so at 6 fps (hint 4) they came every 0.67 s instead of every
 * second (1.49 a second in the logs of 31/10 and 01/11/2026). Since plan 22 the encoder is asked for a longer interval
 * ([DashLogic.encoderKeyframeSec], 2 s = 8 frames on a hint of 4) and the governor asks for the keyframe by the clock at every pace
 * ([DashLogic.paceSyncNeeded]): one a second at 4, 6 and 2 fps. The `idr=` of the windows at `target=6` is what to watch on the dash.
 *
 * It always runs ([com.reau.app.settings.REauSettings.lightScreenFps]; it is no longer a setting).
 *
 * Only the app menu is told with certainty (the display of `GhAppLauncherService`); a music screen and the map are the same display.
 * Pure, so the JVM tests cover it; the pace stays the rider's profile everywhere else.
 */
internal class LightScreen(private val holdMs: Long = HOLD_MS) {
    private var on = false
    private var holdUntilMs = 0L

    /**
     * Whether the faster pace is on at [nowMs]: at once when the screen is light, and [holdMs] after it stops being (the screen's own read already holds a new screen 400 ms, so this only
     * smooths a flicker, and a touch that leaves the menu for the map does not keep the faster pace there). Never when the phone is hot
     * ([thermalOk] false).
     */
    fun update(light: Boolean, thermalOk: Boolean, nowMs: Long): Boolean {
        if (!thermalOk) {
            on = false
        } else if (light) {
            on = true
            holdUntilMs = nowMs + holdMs
        } else if (on && nowMs >= holdUntilMs) {
            on = false
        }
        return on
    }

    companion object {
        const val HOLD_MS = 500L

        /**
         * Whether [screen] asks for the faster pace: the app menu and the other apps (Spotify, the phone...), always; only the navigation (Waze,
         * Google Maps) and a screen not known yet follow the rider's profile.
         */
        fun wanted(screen: com.reau.app.aa.AaScreen): Boolean =
            screen == com.reau.app.aa.AaScreen.MENU || screen == com.reau.app.aa.AaScreen.APPS

        /** The pace's rate on a light screen: [light] fps, never below the [base] one (the profile's). */
        fun fps(base: Int, light: Int): Int = maxOf(base, light)
    }
}
