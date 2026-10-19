package com.reau.app.ui

import com.reau.app.aa.AaCommand
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickMap
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick page's "Chamada" tab says what the calls' controls do; JoystickMap does it. A tab that said "Recusa" for a
 * control that did something else would be worse than none, so each cell of the tab is pressed on a real JoystickMap
 * with the dash's own code for it and must send what the tab says.
 */
class CallPageTest {
    /** The code the dash sends for a control in each moment (what the ride log showed). */
    private fun event(moment: CallMoment, control: Control): DashButtonEvent = when (moment) {
        CallMoment.RINGING -> when (control) {
            Control.LEFT -> DashButtonEvent.Unknown(0x02)
            Control.RIGHT -> DashButtonEvent.Unknown(0x01)
            Control.CENTER -> DashButtonEvent.PlayPause(0x05) // ringing, the middle comes as the media middle
            Control.UP -> DashButtonEvent.VolumeUp(0x06)
            Control.DOWN -> DashButtonEvent.VolumeDown(0x07)
        }
        CallMoment.IN_CALL -> when (control) {
            Control.LEFT -> DashButtonEvent.PrevTrack(0x0A) // what the dash would send; it keeps it to itself
            Control.RIGHT -> DashButtonEvent.NextTrack(0x09)
            Control.CENTER -> DashButtonEvent.Unknown(0x16)
            Control.UP -> DashButtonEvent.Unknown(0x03)
            Control.DOWN -> DashButtonEvent.Unknown(0x04)
        }
    }

    private fun sent(moment: CallMoment, control: Control): List<AaCommand> {
        val state = if (moment == CallMoment.RINGING) CallState.RINGING else CallState.IN_CALL
        return JoystickMap(callState = { state }).press(event(moment, control), 10_000).sent
    }

    @Test
    fun `ringing, each cell of the tab is what the joystick sends`() {
        val m = CallMoment.RINGING
        assertEquals(CallEffect.ANSWER, CallPage.effect(m, Control.LEFT))
        assertEquals(listOf(AaCommand.AnswerCall), sent(m, Control.LEFT))
        assertEquals(CallEffect.REJECT, CallPage.effect(m, Control.RIGHT))
        assertEquals(listOf(AaCommand.EndCall), sent(m, Control.RIGHT))
        assertEquals(CallEffect.REJECT, CallPage.effect(m, Control.CENTER))
        assertEquals(listOf(AaCommand.EndCall), sent(m, Control.CENTER))
        // up and down are the page's, not the call's
        assertEquals(CallEffect.MODE_PAGE, CallPage.effect(m, Control.UP))
        assertTrue(sent(m, Control.UP).none { it == AaCommand.AnswerCall || it == AaCommand.EndCall || it is AaCommand.CallVolume })
        assertEquals(CallEffect.MODE_PAGE, CallPage.effect(m, Control.DOWN))
        assertTrue(sent(m, Control.DOWN).none { it == AaCommand.AnswerCall || it == AaCommand.EndCall || it is AaCommand.CallVolume })
    }

    @Test
    fun `on a call, each cell of the tab is what the joystick sends`() {
        val m = CallMoment.IN_CALL
        assertEquals(CallEffect.END, CallPage.effect(m, Control.CENTER))
        assertEquals(listOf(AaCommand.EndCall), sent(m, Control.CENTER))
        assertEquals(CallEffect.VOLUME_UP, CallPage.effect(m, Control.UP))
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), sent(m, Control.UP))
        assertEquals(CallEffect.VOLUME_DOWN, CallPage.effect(m, Control.DOWN))
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), sent(m, Control.DOWN))
        // the sides: the dash does not send them on a call; if one came as a media code, the left would do nothing
        assertEquals(CallEffect.NOT_SENT, CallPage.effect(m, Control.LEFT))
        assertTrue(sent(m, Control.LEFT).isEmpty())
        assertEquals(CallEffect.NOT_SENT, CallPage.effect(m, Control.RIGHT))
    }

    @Test
    fun `every cell has a short label, a sentence and a note`() {
        for (moment in CallMoment.entries) for (control in Control.entries) {
            val effect = CallPage.effect(moment, control)
            assertTrue("$moment $control", effect.short != 0 && effect.what != 0 && effect.note != 0)
        }
    }
}
