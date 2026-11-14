package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick page: in the dash's map mode, up is the dash's own back and the centre puts it in the media mode, so the two are locked: no
 * action, whatever was saved for them before. Down is REau's info ring by default and can be given another action ([JoystickInfoRingTest]).
 */
class JoystickLocksTest {
    @Test
    fun `up and the centre are locked in the map mode only`() {
        for (gesture in Gesture.entries) {
            assertTrue(JoystickBindings.isLocked(Slot(Mode.MAP, Control.UP, gesture)))
            assertTrue(JoystickBindings.isLocked(Slot(Mode.MAP, Control.CENTER, gesture)))
        }
        for (control in listOf(Control.LEFT, Control.RIGHT, Control.DOWN)) assertFalse(JoystickBindings.isLocked(Slot(Mode.MAP, control, Gesture.ONCE)))
        for (control in Control.entries) assertFalse(JoystickBindings.isLocked(Slot(Mode.MEDIA, control, Gesture.ONCE)))
    }

    @Test
    fun `a choice saved for a locked control does nothing and is not kept`() {
        val page = JoystickBindings.decode("map.center.once=HOME;map.up.once=BACK")
        assertEquals(Action.NOTHING, page[Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)])
        assertEquals(Action.NOTHING, page[Slot(Mode.MAP, Control.UP, Gesture.ONCE)])
        assertEquals("", page.encode())
    }

    @Test
    fun `a page built with a locked choice still does nothing there`() {
        val centre = Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings(mapOf(centre to Action.HOME))[centre])
    }

    @Test
    fun `picking an action for a locked control changes nothing`() {
        val centre = Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(centre, Action.ASSISTANT)[centre])
        assertEquals("", JoystickBindings.DEFAULT.with(centre, Action.ASSISTANT).encode())
    }

    // ── the rider is never left without "back" ─────────────────────────────────────────────────────────────

    private val dashBack = DashButtonEvent.JoystickBack(0x12)
    private val androidAutoBack = listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_BACK))

    /** The first back press of a fresh map on [page], with the phone in [call]: what it sends. */
    private fun firstBack(page: JoystickBindings, call: CallState = CallState.IDLE) =
        JoystickMap(page, assistant = true, callState = { call }).press(dashBack, 0).sent

    @Test
    fun `whatever one control is given, the dash's back is still back`() {
        for (slot in JoystickBindings.SLOTS) for (action in Action.entries) {
            val page = JoystickBindings.DEFAULT.with(slot, action)
            for (call in CallState.entries) assertEquals("${slot.key}=$action, $call", androidAutoBack, firstBack(page, call))
        }
    }

    @Test
    fun `whatever every control is given at once, the dash's back is still back`() {
        // The same action on every slot it can be on, and then nothing anywhere: no page leaves the rider without back.
        for (action in Action.entries) {
            val everywhere = JoystickBindings.SLOTS.fold(JoystickBindings.DEFAULT) { page, slot -> page.with(slot, action) }
            assertEquals("everywhere $action", androidAutoBack, firstBack(everywhere))
        }
        val nothing = JoystickBindings.SLOTS.fold(JoystickBindings.DEFAULT) { page, slot -> page.with(slot, Action.NOTHING) }
        assertEquals(androidAutoBack, firstBack(nothing))
    }

    @Test
    fun `a page read from any saved text, however odd, keeps the dash's back`() {
        val random = java.util.Random(11)
        val words = Action.entries.map { it.name } + listOf("", "?", "BACK;", "twice")
        repeat(500) {
            val text = JoystickBindings.SLOTS.filter { random.nextBoolean() }.joinToString(";") { "${it.key}=${words[random.nextInt(words.size)]}" }
            assertEquals(text, androidAutoBack, firstBack(JoystickBindings.decode(text)))
        }
    }

    @Test
    fun `the map mode's up, the dash's own back, and its centre take no action on any page`() {
        for (action in Action.entries) for (gesture in Gesture.entries) for (control in listOf(Control.UP, Control.CENTER)) {
            val slot = Slot(Mode.MAP, control, gesture)
            assertEquals("$action on ${slot.key}", Action.NOTHING, JoystickBindings.DEFAULT.with(slot, action)[slot])
            assertEquals(Action.NOTHING, JoystickBindings.decode("${slot.key}=$action")[slot])
        }
    }

    @Test
    fun `back is back again after the other controls were pressed, and two backs are home`() {
        val page = JoystickBindings.SLOTS.fold(JoystickBindings.DEFAULT) { p, slot -> p.with(slot, Action.NOTHING) }
        val map = JoystickMap(page)
        map.press(DashButtonEvent.NextTrack(0x09), 0)
        map.press(DashButtonEvent.PlayPause(0x05), 100)
        assertEquals(androidAutoBack, map.press(dashBack, 1_000).sent)
        assertEquals("the second within a moment is home, as before", listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME)), map.press(dashBack, 1_300).sent)
    }
}
