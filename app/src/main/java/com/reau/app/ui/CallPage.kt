package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.JoystickBindings.Control

/** The two moments of a call the tab tells apart. */
enum class CallMoment { RINGING, IN_CALL }

/** What a tap of the joystick does for the phone's call, as the joystick page's "Chamada" tab says it (read only). */
enum class CallEffect(val short: Int, val what: Int, val note: Int) {
    ANSWER(R.string.call_short_answer, R.string.call_what_answer, R.string.call_note_answer),
    REJECT(R.string.call_short_reject, R.string.call_what_reject, R.string.call_note_reject),
    END(R.string.call_short_end, R.string.call_what_end, R.string.call_note_end),
    VOLUME_UP(R.string.call_short_volume_up, R.string.call_what_volume_up, R.string.call_note_volume),
    VOLUME_DOWN(R.string.call_short_volume_down, R.string.call_what_volume_down, R.string.call_note_volume),
    MODE_PAGE(R.string.call_short_mode, R.string.call_what_mode, R.string.call_note_mode),
    NOT_SENT(R.string.call_short_not_sent, R.string.call_what_not_sent, R.string.call_note_not_sent),
}

/**
 * The calls' controls, with nothing to pick: the same in the dash's two modes. Ringing, the left answers and the
 * right and the middle reject; up and down stay the page's. On a call the dash keeps the left and the right to itself,
 * the middle ends it and up and down are the call's volume. JoystickMap does it; CallPageTest holds the two together.
 */
object CallPage {
    fun effect(moment: CallMoment, control: Control): CallEffect = when (moment) {
        CallMoment.RINGING -> when (control) {
            Control.LEFT -> CallEffect.ANSWER
            Control.RIGHT, Control.CENTER -> CallEffect.REJECT
            Control.UP, Control.DOWN -> CallEffect.MODE_PAGE
        }
        CallMoment.IN_CALL -> when (control) {
            Control.LEFT, Control.RIGHT -> CallEffect.NOT_SENT
            Control.CENTER -> CallEffect.END
            Control.UP -> CallEffect.VOLUME_UP
            Control.DOWN -> CallEffect.VOLUME_DOWN
        }
    }
}
