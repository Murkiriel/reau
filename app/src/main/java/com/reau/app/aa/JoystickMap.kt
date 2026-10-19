package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState

/** What a dash button becomes: keys and wheel steps for Android Auto, or a call answered or ended on the phone. */
sealed class AaCommand {
    data class Key(val keycode: Int) : AaCommand()
    data class Scroll(val delta: Int) : AaCommand()
    /** Several, in order. */
    data class Steps(val commands: List<AaCommand>) : AaCommand()
    /** A finger from the phone's "Tela do Android Auto", at a point of the video. */
    data class Touch(val action: PhoneTouch.Action, val x: Int, val y: Int) : AaCommand()
    /** Not for Android Auto: the phone answers the ringing call ([com.reau.app.phone.PhoneCalls]). */
    data object AnswerCall : AaCommand()
    /** Not for Android Auto: the phone rejects the ringing call or ends the one going on. */
    data object EndCall : AaCommand()
    /** Not for Android Auto: the phone's call volume one [step] up (1) or down (-1), as the dash's own codes ask while a call goes on. */
    data class CallVolume(val step: Int) : AaCommand()
}

/**
 * The dash's joystick through the rider's page ([JoystickBindings]).
 *
 * Which control: the dash tells its mode by the codes it sends. In its media mode the sides come as next and previous
 * track (0x09 right, 0x0A left), up and down as volume (0x06, 0x07) and the middle as play/pause (0x05); in its map
 * mode the sides come as zoom (0x13 right, 0x14 left: a capture on the bike and better-dash), the middle as
 * IN (0x18) and down as 0x15 (better-dash reads it so; still to be confirmed on the bike). 0x0B comes once as
 * the dash connects and is no button: ignored. Back (0x12) is not on the page: back, and twice within
 * [DOUBLE_BACK_MS] home, as before.
 *
 * One tap, one action, at once (a double tap does not work well on the dash's joystick). "Held" never fires (as far
 * as is known, one code per press, no release).
 *
 * The calls use the same sides, above the page, one tap: while [callState] says
 * the phone rings, left answers and right rejects; on a call, right ends it and left does nothing (the
 * sides of Android Auto's call card); while it rings the middle rejects too; up and down go through the page. While the phone rings the dash
 * shows its own call screen, and the first tap on a side comes as 0x02 (left) or 0x01 (right), as seen on a real dash;
 * the dash then goes back to its map, so a tap after it comes as the map's side. On a call the dash keeps the sides
 * to itself and sends its middle as 0x16, which ends the call as the right does. For
 * [CALL_QUIET_MS] after a call was handled the control that did it sends nothing, so the same tap again neither ends
 * the call just answered nor moves the focus; another control counts at once (the middle ending a call just answered
 * was held back in use). [callState] is always idle unless the rider switched
 * answering on.
 */
class JoystickMap(
    @Volatile var bindings: JoystickBindings = JoystickBindings.DEFAULT,
    private val assistant: Boolean = false,
    private val callState: () -> CallState = { CallState.IDLE },
) {
    /** What a tap gave: what to send, and the ride log's words. */
    data class Press(val sent: List<AaCommand>, val lines: List<String>)

    private var lastBackMs = Long.MIN_VALUE / 2
    private var quietUntilMs = Long.MIN_VALUE / 2
    // The control that answered, rejected or ended the call: only it is held back for CALL_QUIET_MS (the same tap again
    // is the accident; another control right after, such as the middle ending a call just answered, is meant).
    private var quietControl: Control? = null
    private var lastCode = -1
    private var lastCodeMs = Long.MIN_VALUE / 2
    private var lastSideMs = Long.MIN_VALUE / 2

    @Synchronized
    fun press(event: DashButtonEvent, nowMs: Long): Press {
        val place = whereOf(event)
        // The dash's bounce (the same code again within BOUNCE_MS; a 0x05 has come
        // twice about 20 ms apart) and two codes in one gesture (a centre or up within
        // COMBO_MS after a side) are not taps.
        val bounce = event.code == lastCode && nowMs - lastCodeMs < BOUNCE_MS
        lastCode = event.code
        lastCodeMs = nowMs
        val callScreen = callScreenSide(event)
        return byCode(event, bounce) ?: when {
            event is DashButtonEvent.JoystickBack -> backPress(nowMs)
            callScreen != null -> {
                if (callScreen != Control.CENTER) lastSideMs = nowMs
                callScreenPress(callScreen, nowMs) ?: Press(emptyList(), listOf("not mapped -> nothing"))
            }
            place == null -> Press(emptyList(), listOf("not mapped -> nothing"))
            place.second in AFTER_SIDE && nowMs - lastSideMs < COMBO_MS -> Press(emptyList(), listOf("right after a side, ignored"))
            // While the phone rings the dash's middle comes as its media or map middle, not from its call screen (a
            // ride's play/pause there started or stopped the music under the call): it rejects, as the right does.
            place.second == Control.CENTER && callState() == CallState.RINGING -> callScreenPress(Control.CENTER, nowMs)
                ?: Press(emptyList(), listOf("not mapped -> nothing"))
            else -> {
                if (place.second == Control.LEFT || place.second == Control.RIGHT) lastSideMs = nowMs
                callFirst(place.second, nowMs) ?: onPage(place.first, place.second)
            }
        }
    }

    /**
     * The control of the dash's own call screen: while the phone rings 0x02 is the left and 0x01 the right; on a call
     * 0x16 is the middle (the sides do not come then, as far as observed) and 0x01 is still taken as the right.
     */
    private fun callScreenSide(event: DashButtonEvent): Control? {
        val code = (event as? DashButtonEvent.Unknown)?.code ?: return null
        val state = callState()
        return when {
            code == CALL_SCREEN_LEFT && state == CallState.RINGING -> Control.LEFT
            code == CALL_SCREEN_RIGHT && state != CallState.IDLE -> Control.RIGHT
            code == CALL_SCREEN_MIDDLE && state == CallState.IN_CALL -> Control.CENTER
            else -> null
        }
    }

    /** A tap on the dash's call screen: the sides as the calls' sides, and on a call the middle ends it too. */
    private fun callScreenPress(control: Control, nowMs: Long): Press? = when {
        control != Control.CENTER -> callFirst(control, nowMs)
        nowMs < quietUntilMs && quietControl == Control.CENTER -> Press(emptyList(), listOf("center: a call was just handled, nothing"))
        else -> {
            quietUntilMs = nowMs + CALL_QUIET_MS
            quietControl = Control.CENTER
            Press(listOf(AaCommand.EndCall), listOf("call: center -> EndCall"))
        }
    }

    /** A tap through the rider's page. */
    private fun onPage(mode: Mode, control: Control): Press {
        val slot = Slot(mode, control, Gesture.ONCE)
        val command = bindings[slot].command(assistant)
        val line = "${name(mode, control)} once -> ${command ?: Action.NOTHING.name.lowercase()}"
        return Press(listOfNotNull(command), listOf(line))
    }

    /** What the code alone settles: the dash's notice, a bounce, and the call's volume (before any side or page). */
    private fun byCode(event: DashButtonEvent, bounce: Boolean): Press? {
        val unknown = event as? DashButtonEvent.Unknown
        return when {
            unknown?.code == DASH_NOTICE -> Press(emptyList(), listOf("the dash's notice, ignored"))
            bounce -> Press(emptyList(), listOf("bounce, ignored"))
            unknown != null && unknown.code in CALL_VOLUME && callState() == CallState.IN_CALL -> callVolume(unknown.code)
            else -> null
        }
    }

    /** While a call goes on the dash sends 0x03 for volume up and 0x04 for down. */
    private fun callVolume(code: Int): Press {
        val step = if (code == CALL_VOLUME_UP) 1 else -1
        return Press(listOf(AaCommand.CallVolume(step)), listOf("call volume ${if (step > 0) "+1" else "-1"}"))
    }

    private fun backPress(nowMs: Long): Press {
        val key = back(nowMs)
        return Press(listOf(key), listOf("back -> $key"))
    }

    /** The calls, above the page: a [Press] when the tap was the call's, null when it goes through the page. */
    private fun callFirst(control: Control, nowMs: Long): Press? {
        val side = control.takeIf { it == Control.LEFT || it == Control.RIGHT } ?: return null
        val state = callState()
        val call = callAction(state, side)
        val name = side.name.lowercase()
        return when {
            nowMs < quietUntilMs && quietControl == side -> Press(emptyList(), listOf("$name: a call was just handled, nothing"))
            call != null -> {
                quietUntilMs = nowMs + CALL_QUIET_MS
                quietControl = side
                Press(listOf(call), listOf("call: $name -> $call"))
            }
            state == CallState.IN_CALL -> Press(emptyList(), listOf("$name: on a call, nothing"))
            else -> null
        }
    }

    private fun back(nowMs: Long): AaCommand {
        val double = nowMs - lastBackMs <= DOUBLE_BACK_MS
        // A pair is used once: the back after a home starts a new pair.
        lastBackMs = if (double) Long.MIN_VALUE / 2 else nowMs
        return AaCommand.Key(if (double) ServiceDiscoveryResponse.KEY_HOME else ServiceDiscoveryResponse.KEY_BACK)
    }

    companion object {
        /** The ride log's line for a button (with the state of the call, to read a press made in one). */
        fun logLine(name: String, code: Int, lines: List<String>, call: String): String =
            "button $name 0x${"%02X".format(code)} -> ${lines.ifEmpty { listOf("nothing") }.joinToString("; ")} [call=$call]"

        const val DOUBLE_BACK_MS = 700L
        /** A ride: a second tap on the right came 1.7 s after the reject and moved the focus. */
        const val CALL_QUIET_MS = 3_000L
        const val BOUNCE_MS = 60L
        const val COMBO_MS = 100L

        /** The controls a side can bring along in the same gesture. */
        private val AFTER_SIDE = setOf(Control.CENTER, Control.UP)

        private const val CALL_VOLUME_UP = 0x03
        private val CALL_VOLUME = setOf(CALL_VOLUME_UP, 0x04)

        /** The sides of the dash's own call screen, while the phone rings. */
        private const val CALL_SCREEN_LEFT = 0x02
        private const val CALL_SCREEN_RIGHT = 0x01
        private const val CALL_SCREEN_MIDDLE = 0x16

        /** Sent once as the dash connects; no button. */
        private const val DASH_NOTICE = 0x0B

        /** Down in the dash's map mode, as better-dash reads it. */
        private const val MAP_DOWN = 0x15

        /** Which mode and control a code is; null for what the page does not know. */
        fun whereOf(event: DashButtonEvent): Pair<Mode, Control>? = when (event) {
            is DashButtonEvent.NextTrack -> Mode.MEDIA to Control.RIGHT
            is DashButtonEvent.PrevTrack -> Mode.MEDIA to Control.LEFT
            is DashButtonEvent.VolumeUp -> Mode.MEDIA to Control.UP
            is DashButtonEvent.VolumeDown -> Mode.MEDIA to Control.DOWN
            is DashButtonEvent.PlayPause -> Mode.MEDIA to Control.CENTER
            // 0x13 is the right and 0x14 the left (a capture on the bike, better-dash).
            is DashButtonEvent.ZoomOut -> Mode.MAP to Control.RIGHT
            is DashButtonEvent.ZoomIn -> Mode.MAP to Control.LEFT
            is DashButtonEvent.JoystickIn -> Mode.MAP to Control.CENTER
            is DashButtonEvent.Unknown -> if (event.code == MAP_DOWN) Mode.MAP to Control.DOWN else null
            else -> null
        }
    }
}

/**
 * The call's sides, as Android Auto's call
 * card, green on the left and red on the right: ringing, left answers and right rejects; on a call, right ends it.
 */
private fun callAction(state: CallState, side: Control): AaCommand? = when (state) {
    CallState.RINGING -> if (side == Control.LEFT) AaCommand.AnswerCall else AaCommand.EndCall
    CallState.IN_CALL -> if (side == Control.RIGHT) AaCommand.EndCall else null
    CallState.IDLE -> null
}

/** A control as the ride log names it: `media right`. */
private fun name(mode: Mode, control: Control) = "${mode.name.lowercase()} ${control.name.lowercase()}"
