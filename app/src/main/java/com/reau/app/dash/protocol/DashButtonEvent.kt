// The call and track events stay: they are what the dash may send. PlayPause, VolumeUp and VolumeDown are the media
// mode's other codes, found by testing the dash's media mode.
package com.reau.app.dash.protocol

/**
 * Semantic mapping of the raw `09 00` joystick/button byte the dash sends (see
 * [com.reau.app.dash.DashSession.onButton]) — replaces two previously-divergent, unused
 * constant tables (`DashCommands.BTN_05..BTN_22`, never consumed, and an older view model's own
 * `BTN_MAP_ZOOM_IN`/etc, which WERE the ones actually driving the `when`) with a single mapper
 * both the ack path and the app-action path can share.
 *
 * [NextTrack]/[PrevTrack] are unverified on our hardware — taken from another project
 * (DashMediaControl.swift): their dash forwards these same joystick
 * codes (0x09/0x0A) as next/previous-track ONLY while the dash's own now-playing screen is up,
 * the map's zoom codes (0x14/0x13) otherwise. We never put the dash in that state (the "now
 * playing" widget is deliberately not wired, to stay clear of Play Protect),
 * so these two should never actually arrive in our stream; kept mapped (not [Unknown]) so if they
 * ever do, [com.reau.app.data.RideDiagnostics] logs a recognizable label instead of a
 * bare hex code, confirming or refuting the hypothesis from a ride log.
 */
sealed class DashButtonEvent {
    abstract val code: Int

    data class ZoomIn(override val code: Int) : DashButtonEvent()
    data class ZoomOut(override val code: Int) : DashButtonEvent()
    data class NextTrack(override val code: Int) : DashButtonEvent()
    data class PrevTrack(override val code: Int) : DashButtonEvent()
    /** Dash's own joystick-IN — believed to enter the dash's native media mode (unconfirmed). */
    data class JoystickIn(override val code: Int) : DashButtonEvent()
    /** Dash's own joystick-BACK — believed to exit the dash's native media mode (unconfirmed). */
    data class JoystickBack(override val code: Int) : DashButtonEvent()
    /** No confirmed dash button code produces this yet — see [fromCode]'s doc. Meant for answering
     *  the ringing call; this app only logs it. */
    data class AnswerCall(override val code: Int) : DashButtonEvent()
    /** See [AnswerCall]'s doc — same status. */
    data class RejectCall(override val code: Int) : DashButtonEvent()
    /** The middle of the joystick while the dash is in its own media mode. */
    data class PlayPause(override val code: Int) : DashButtonEvent()
    /** The joystick's up side in the dash's media mode. */
    data class VolumeUp(override val code: Int) : DashButtonEvent()
    /** The joystick's down side in the dash's media mode (fw 11.63: DOWN = 0x07). */
    data class VolumeDown(override val code: Int) : DashButtonEvent()
    data class Unknown(override val code: Int) : DashButtonEvent()

    companion object {
        private const val ZOOM_IN = 0x14
        private const val ZOOM_OUT = 0x13
        private const val NEXT_TRACK = 0x09
        private const val PREV_TRACK = 0x0A
        private const val JOYSTICK_IN = 0x18
        private const val JOYSTICK_BACK = 0x12
        private const val PLAY_PAUSE = 0x05
        private const val VOLUME_UP = 0x06
        private const val VOLUME_DOWN = 0x07

        /** [rawByte] is the last byte of the `09 00` TLV value — same input DashSession already
         *  extracts before acking it. Masked to 0..255 so a negative Kotlin Byte round-trips correctly. */
        fun fromByte(rawByte: Byte): DashButtonEvent = fromCode(rawByte.toInt() and 0xFF)

        fun fromCode(code: Int): DashButtonEvent = when (code) {
            ZOOM_IN -> ZoomIn(code)
            ZOOM_OUT -> ZoomOut(code)
            NEXT_TRACK -> NextTrack(code)
            PREV_TRACK -> PrevTrack(code)
            JOYSTICK_IN -> JoystickIn(code)
            JOYSTICK_BACK -> JoystickBack(code)
            PLAY_PAUSE -> PlayPause(code)
            VOLUME_UP -> VolumeUp(code)
            VOLUME_DOWN -> VolumeDown(code)
            else -> Unknown(code)
        }
    }
}
