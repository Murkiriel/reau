package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick through the rider's page, one tap at a time. A double tap does not work well on the joystick, so every
 * tap goes at once and nothing waits for a second. "Held" waits for a test on the bike. The calls use the same sides,
 * above the page, with one tap; as Android Auto's call card, green on the left and red on the right: ringing, left
 * answers and right rejects; on a call, right ends it and left does nothing (it was the other way round; changed and
 * declared).
 *
 * Removed with "twice": the wait for a second tap, the pair used once, the waiting tap let go by another
 * control, down twice playing, up twice calling Gemini, and the calls answered with the same side twice.
 */
class JoystickGesturesTest {
    private val right = DashButtonEvent.NextTrack(0x09)
    private val left = DashButtonEvent.PrevTrack(0x0A)
    private val up = DashButtonEvent.VolumeUp(0x06)
    private val down = DashButtonEvent.VolumeDown(0x07)
    private val center = DashButtonEvent.PlayPause(0x05)
    // The dash's map mode: 0x13 is the right and 0x14 the left (better-dash's capture on the bike).
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mapLeft = DashButtonEvent.ZoomIn(0x14)
    private val joystickIn = DashButtonEvent.JoystickIn(0x18)
    private val back = DashButtonEvent.JoystickBack(0x12)

    @Test
    fun `every tap of the media mode goes at once`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 0).sent)
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(left, 100).sent)
        assertEquals("the up control starts as nothing", emptyList<AaCommand>(), m.press(up, 200).sent)
        assertEquals(listOf(AaCommand.Key(3)), m.press(down, 300).sent)
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 400).sent)
    }

    @Test
    fun `two quick taps on a side are two steps of the focus`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 0).sent)
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 150).sent)
    }

    @Test
    fun `held does nothing until the bike test`() {
        val m = JoystickMap(JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.CENTER, Gesture.HOLD), Action.HOME))
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 0).sent)
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 100).sent)
    }

    @Test
    fun `in the map mode the sides move the focus and the rest does nothing by default`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(mapRight, 0).sent)
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(mapLeft, 100).sent)
        assertTrue(m.press(joystickIn, 200).sent.isEmpty())
        assertTrue(m.press(DashButtonEvent.Unknown(0x15), 300).sent.isEmpty())
    }

    @Test
    fun `the map mode follows its own slots`() {
        val chosen = JoystickMap(
            JoystickBindings.DEFAULT
                .with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.NEXT_SONG)
                .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.PREVIOUS_SONG)
                .with(Slot(Mode.MAP, Control.CENTER, Gesture.ONCE), Action.CONFIRM),
        )
        assertEquals(listOf(AaCommand.Key(87)), chosen.press(mapRight, 0).sent)
        assertEquals(listOf(AaCommand.Key(88)), chosen.press(mapLeft, 1_000).sent)
        // The map mode's centre puts the dash in the media mode; locked, it sends nothing. Declared.
        assertTrue(chosen.press(joystickIn, 2_000).sent.isEmpty())
    }

    @Test
    fun `Gemini on a slot of its own, with its switch`() {
        val page = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.ASSISTANT)
        assertEquals(listOf(AaCommand.Key(84)), JoystickMap(page, assistant = true).press(up, 0).sent)
        assertTrue("without the microphone, nothing", JoystickMap(page, assistant = false).press(up, 0).sent.isEmpty())
    }

    @Test
    fun `0x15 is down in the map mode, and 0x0B is the dash's notice, ignored`() {
        val m = JoystickMap(JoystickBindings.DEFAULT.with(Slot(Mode.MAP, Control.DOWN, Gesture.ONCE), Action.HOME))
        val down = m.press(DashButtonEvent.Unknown(0x15), 0)
        assertEquals(listOf(AaCommand.Key(3)), down.sent)
        assertEquals(listOf("map down once -> Key(keycode=3)"), down.lines)
        val notice = m.press(DashButtonEvent.Unknown(0x0B), 1_000)
        assertTrue(notice.sent.isEmpty())
        assertEquals(listOf("the dash's notice, ignored"), notice.lines)
        assertEquals(listOf("not mapped -> nothing"), m.press(DashButtonEvent.Unknown(0x42), 2_000).lines)
    }

    @Test
    fun `the log says what each tap became`() {
        val m = JoystickMap()
        assertEquals(listOf("media right once -> Scroll(delta=1)"), m.press(right, 0).lines)
        assertEquals(listOf("map center once -> nothing"), m.press(joystickIn, 100).lines)
    }

    @Test
    fun `new choices count from the next tap`() {
        val m = JoystickMap()
        m.bindings = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE), Action.BACK)
        assertEquals(listOf(AaCommand.Key(4)), m.press(center, 0).sent)
    }

    @Test
    fun `back alone is back, back twice is home, as before`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Key(4)), m.press(back, 0).sent)
        assertEquals(listOf(AaCommand.Key(3)), m.press(back, 500).sent)
        assertEquals(listOf(AaCommand.Key(4)), m.press(back, 900).sent)
    }

    // ── the calls, on the same sides, one tap ──────────────────────────────────────────────────────────────

    @Test
    fun `with the phone ringing, one tap left answers and one tap right rejects, in either mode`() {
        assertEquals(listOf(AaCommand.AnswerCall), JoystickMap(callState = { CallState.RINGING }).press(left, 0).sent)
        assertEquals(listOf(AaCommand.EndCall), JoystickMap(callState = { CallState.RINGING }).press(right, 0).sent)
        assertEquals(listOf(AaCommand.AnswerCall), JoystickMap(callState = { CallState.RINGING }).press(mapLeft, 0).sent)
        assertEquals(listOf(AaCommand.EndCall), JoystickMap(callState = { CallState.RINGING }).press(mapRight, 0).sent)
    }

    @Test
    fun `on a call, one tap right ends it, left does nothing, the rest goes through the page`() {
        val m = JoystickMap(callState = { CallState.IN_CALL })
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 0).sent)
        val leftTap = m.press(left, 2_000)
        assertTrue("left does nothing on a call", leftTap.sent.isEmpty())
        assertEquals(listOf("left: on a call, nothing"), leftTap.lines)
        assertEquals(listOf(AaCommand.EndCall), m.press(right, 4_000).sent)
        assertEquals(listOf(AaCommand.EndCall), JoystickMap(callState = { CallState.IN_CALL }).press(mapRight, 0).sent)
    }

    // ── the dash's own call screen while the phone rings: the first tap on a side is 0x02 (left) or 0x01 (right) ──

    private val callScreenLeft = DashButtonEvent.Unknown(0x02)
    private val callScreenRight = DashButtonEvent.Unknown(0x01)

    @Test
    fun `with the phone ringing, the first tap on the dash's call screen answers on the left and rejects on the right`() {
        val answered = JoystickMap(callState = { CallState.RINGING }).press(callScreenLeft, 0)
        assertEquals(listOf(AaCommand.AnswerCall), answered.sent)
        assertEquals(listOf("call: left -> AnswerCall"), answered.lines)
        assertEquals(listOf(AaCommand.EndCall), JoystickMap(callState = { CallState.RINGING }).press(callScreenRight, 0).sent)
    }

    @Test
    fun `a map side right after the call screen's tap is held back, not a second action`() {
        // The ride: 0x01 on the call screen, then 0x13 two seconds later as the dash was back on its map.
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.EndCall), m.press(callScreenRight, 0).sent)
        state = CallState.IDLE
        assertTrue(m.press(mapRight, JoystickMap.CALL_QUIET_MS - 1).sent.isEmpty())
    }

    @Test
    fun `with no call the call screen's codes do nothing, as before`() {
        assertEquals(listOf("not mapped -> nothing"), JoystickMap().press(callScreenLeft, 0).lines)
        assertTrue(JoystickMap().press(callScreenRight, 0).sent.isEmpty())
        assertEquals(listOf("not mapped -> nothing"), JoystickMap().press(callScreenMiddle, 0).lines)
    }

    // ── on a call the dash keeps its sides and sends its middle as 0x16 ──

    private val callScreenMiddle = DashButtonEvent.Unknown(0x16)

    @Test
    fun `on a call, the middle of the dash's call screen ends it, and its right too`() {
        val ended = JoystickMap(callState = { CallState.IN_CALL }).press(callScreenMiddle, 0)
        assertEquals(listOf(AaCommand.EndCall), ended.sent)
        assertEquals(listOf("call: center -> EndCall"), ended.lines)
        assertEquals(listOf(AaCommand.EndCall), JoystickMap(callState = { CallState.IN_CALL }).press(callScreenRight, 0).sent)
        assertTrue("left does nothing on a call", JoystickMap(callState = { CallState.IN_CALL }).press(callScreenLeft, 0).sent.isEmpty())
    }

    @Test
    fun `the call screen's middle ends a call going on only, ringing it does nothing`() {
        assertTrue(JoystickMap(callState = { CallState.RINGING }).press(callScreenMiddle, 0).sent.isEmpty())
    }

    @Test
    fun `a second middle right after the call was ended does nothing`() {
        var state = CallState.IN_CALL
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.EndCall), m.press(callScreenMiddle, 0).sent)
        assertTrue(m.press(callScreenMiddle, 400).sent.isEmpty())
        state = CallState.IDLE
        assertTrue(m.press(callScreenMiddle, 900).sent.isEmpty())
    }

    // ── the call's volume: the dash's own codes while a call goes on (0x03 raises, 0x04 lowers the call volume) ──

    private val callVolumeUp = DashButtonEvent.Unknown(0x03)
    private val callVolumeDown = DashButtonEvent.Unknown(0x04)

    @Test
    fun `on a call, the dash's 0x03 raises the call volume one step and 0x04 lowers it`() {
        val m = JoystickMap(callState = { CallState.IN_CALL })
        val up = m.press(callVolumeUp, 0)
        assertEquals(listOf(AaCommand.CallVolume(1)), up.sent)
        assertEquals(listOf("call volume +1"), up.lines)
        val down = m.press(callVolumeDown, 1_000)
        assertEquals(listOf(AaCommand.CallVolume(-1)), down.sent)
        assertEquals(listOf("call volume -1"), down.lines)
    }

    @Test
    fun `the call volume is for a call going on only, with no call or a ringing one the codes do nothing as before`() {
        assertTrue(JoystickMap().press(callVolumeUp, 0).sent.isEmpty())
        assertEquals(listOf("not mapped -> nothing"), JoystickMap().press(callVolumeDown, 0).lines)
        assertTrue(JoystickMap(callState = { CallState.RINGING }).press(callVolumeUp, 0).sent.isEmpty())
    }

    @Test
    fun `the call volume is no key for Android Auto, and a call just handled does not hold it back`() {
        assertEquals(false, FocusClearer.toAndroidAuto(listOf(AaCommand.CallVolume(1))))
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.AnswerCall), m.press(left, 0).sent)
        state = CallState.IN_CALL
        assertEquals("a volume tap is not a side: the quiet time of the call does not apply", listOf(AaCommand.CallVolume(1)), m.press(callVolumeUp, 300).sent)
    }

    @Test
    fun `the dash's bounce of the same volume code is one step`() {
        val m = JoystickMap(callState = { CallState.IN_CALL })
        assertEquals(listOf(AaCommand.CallVolume(1)), m.press(callVolumeUp, 0).sent)
        assertTrue(m.press(callVolumeUp, JoystickMap.BOUNCE_MS - 1).sent.isEmpty())
        assertEquals(listOf(AaCommand.CallVolume(1)), m.press(callVolumeUp, 2 * JoystickMap.BOUNCE_MS + 1).sent)
    }

    @Test
    fun `right after a call was handled the same control sends nothing for a moment, then moves the focus again`() {
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.EndCall), m.press(right, 0).sent)
        state = CallState.IDLE
        assertTrue(m.press(right, 400).sent.isEmpty())
        assertTrue(m.press(right, JoystickMap.CALL_QUIET_MS - 1).sent.isEmpty())
        // One ms after the tap before it is a bounce; the next real tap, past it.
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, JoystickMap.CALL_QUIET_MS + JoystickMap.BOUNCE_MS).sent)
    }

    @Test
    fun `another control right after answering counts at once, so the middle ends the call just answered`() {
        // A ride: answered with the left, the middle 2,9 s later was held back and the call went on.
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.AnswerCall), m.press(callScreenLeft, 0).sent)
        state = CallState.IN_CALL
        assertEquals(listOf(AaCommand.EndCall), m.press(callScreenMiddle, 2_900).sent)
    }

    @Test
    fun `while the phone rings the middle rejects, in either mode, and plays or pauses again with no call`() {
        // A ride: the middle pressed while ringing came as the media middle (0x05) and went out as play/pause; it
        // was then changed to reject, as the right does.
        val ringing = JoystickMap(callState = { CallState.RINGING })
        val press = ringing.press(center, 0)
        assertEquals(listOf(AaCommand.EndCall), press.sent)
        assertEquals(listOf("call: center -> EndCall"), press.lines)
        assertEquals(listOf(AaCommand.EndCall), JoystickMap(callState = { CallState.RINGING }).press(joystickIn, 0).sent)
        assertEquals("with no call the page's middle again", listOf(AaCommand.Key(23)), JoystickMap().press(center, 0).sent)
    }

    @Test
    fun `a second ring right after the middle rejected, the middle is held back and the left answers`() {
        val m = JoystickMap(callState = { CallState.RINGING })
        assertEquals(listOf(AaCommand.EndCall), m.press(center, 0).sent)
        assertTrue("the same control again within the quiet time", m.press(center, 1_700).sent.isEmpty())
        assertEquals(listOf(AaCommand.AnswerCall), m.press(left, 1_800).sent)
    }

    @Test
    fun `answered with the left, a right tap right after ends the call`() {
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.AnswerCall), m.press(left, 0).sent)
        state = CallState.IN_CALL
        assertEquals(listOf(AaCommand.EndCall), m.press(right, 400).sent)
    }

    @Test
    fun `a tap 1,7 s after a reject is still held back, as on the ride`() {
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(listOf(AaCommand.EndCall), m.press(mapRight, 0).sent)
        state = CallState.IDLE
        assertTrue(m.press(mapRight, 1_700).sent.isEmpty())
    }

    @Test
    fun `with no call the sides are the page's`() {
        val m = JoystickMap(callState = { CallState.IDLE })
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 0).sent)
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(left, 100).sent)
    }

    // ── the dash's bounce and two codes in one gesture ───────────────────────────────────────────────────────
    // A 0x05 came twice 22 ms apart, and four times a side came with a centre or up 12 to 74 ms after it.

    @Test
    fun `the same code again within 60 ms is the dash bouncing, ignored`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 0).sent)
        val bounce = m.press(right, 22)
        assertTrue(bounce.sent.isEmpty())
        assertEquals(listOf("bounce, ignored"), bounce.lines)
        assertEquals("a new tap 78 ms after the bounce counts", listOf(AaCommand.Scroll(1)), m.press(right, 100).sent)
    }

    @Test
    fun `a centre or up within 100 ms after a side is part of the same gesture, ignored`() {
        val m = JoystickMap()
        m.press(right, 0)
        assertTrue(m.press(center, 50).sent.isEmpty())
        m.press(left, 1_000)
        val up = m.press(this.up, 1_030)
        assertTrue(up.sent.isEmpty())
        assertEquals(listOf("right after a side, ignored"), up.lines)
        m.press(mapRight, 2_000)
        assertTrue("the map mode's centre too", m.press(joystickIn, 2_074).sent.isEmpty())
    }

    @Test
    fun `a centre 100 ms after a side, and a side right after a centre, count`() {
        val m = JoystickMap()
        m.press(right, 0)
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 100).sent)
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 110).sent)
    }

    /** The log line of each button says the call state at that moment, to read a press made in a call. */
    @Test
    fun `the log line of a button says the code, what was done and the state of the call`() {
        assertEquals(
            "button Unknown 0x13 -> call volume +1 [call=IN_CALL]",
            JoystickMap.logLine("Unknown", 0x13, listOf("call volume +1"), "IN_CALL"),
        )
        assertEquals("button Back 0x12 -> a; b [call=IDLE]", JoystickMap.logLine("Back", 0x12, listOf("a", "b"), "IDLE"))
        assertEquals("button Unknown 0x0B -> nothing [call=RINGING]", JoystickMap.logLine("Unknown", 0x0B, emptyList(), "RINGING"))
    }
}
