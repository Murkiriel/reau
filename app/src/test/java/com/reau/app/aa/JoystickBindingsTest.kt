package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The joystick page: every control of the dash's joystick, in the media mode and in the map mode, once and held,
 * with the action the rider picks. "Twice" went (a double tap does not work well on the joystick). In the media mode
 * the defaults are what the joystick did once before the page; in the map mode only the sides move the focus (and down is the info ring's switch). The
 * calls act above the page.
 */
class JoystickBindingsTest {
    private val defaults = JoystickBindings.DEFAULT

    // No "twice" any more.
    @Test
    fun `the media mode starts as the joystick was, one tap each`() {
        val expected = mapOf(
            Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE) to Action.WHEEL_PREVIOUS,
            Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE) to Action.WHEEL_NEXT,
            Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE) to Action.HOME,
            Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE) to Action.CONFIRM,
        )
        for ((slot, action) in expected) assertEquals("$slot", action, defaults[slot])
        val rest = JoystickBindings.SLOTS.filter { it.mode == Mode.MEDIA && it !in expected }
        assertEquals("the held slots do nothing: $rest", setOf(Action.NOTHING), rest.map { defaults[it] }.toSet())
    }

    @Test
    fun `the music card needs a music control on the media mode`() {
        assertEquals("the page's own start has none", false, JoystickBindings.DEFAULT.mediaControls)
        assertEquals("the page a new install starts with has no music control: the dash gets no music card until one is put there", false, JoystickBindings.decode(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK).mediaControls)
        for (action in listOf(Action.PREVIOUS_SONG, Action.PLAY_PAUSE, Action.NEXT_SONG)) {
            assertEquals("$action on the media up", true, JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), action).mediaControls)
        }
        assertEquals("confirm is no music control", false, JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.CONFIRM).mediaControls)
        assertEquals("the map mode does not count", false, JoystickBindings.DEFAULT.with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.NEXT_SONG).mediaControls)
        assertEquals("held does not count", false, JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.HOLD), Action.PLAY_PAUSE).mediaControls)
    }

    // In the map mode the sides move the focus too; the rest does nothing.
    @Test
    fun `in the map mode only the sides move the focus`() {
        assertEquals(Action.WHEEL_PREVIOUS, defaults[Slot(Mode.MAP, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.WHEEL_NEXT, defaults[Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE)])
        val rest = JoystickBindings.SLOTS.filter {
            it.mode == Mode.MAP && !(it.gesture == Gesture.ONCE && (it.control == Control.LEFT || it.control == Control.RIGHT))
        }
        assertEquals(8, rest.size)
        assertEquals("down is the info ring's switch", Action.INFO_RING, defaults[JoystickBindings.INFO_RING_SLOT])
        assertEquals(setOf(Action.NOTHING), rest.filter { it != JoystickBindings.INFO_RING_SLOT }.map { defaults[it] }.toSet())
    }

    // Once and held, five controls, two modes.
    @Test
    fun `ten slots in each mode, once and held`() {
        assertEquals(listOf(Gesture.ONCE, Gesture.HOLD), Gesture.entries)
        assertEquals(20, JoystickBindings.SLOTS.size)
        assertEquals(20, JoystickBindings.SLOTS.toSet().size)
    }

    @Test
    fun `a choice is kept, and only the choices are written`() {
        val changed = defaults.with(Slot(Mode.MEDIA, Control.CENTER, Gesture.HOLD), Action.HOME)
            .with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.NEXT_SONG)
        assertEquals(Action.HOME, changed[Slot(Mode.MEDIA, Control.CENTER, Gesture.HOLD)])
        assertEquals("media.center.hold=HOME;map.right.once=NEXT_SONG", changed.encode())
        assertEquals(changed, JoystickBindings.decode(changed.encode()))
        assertEquals("the defaults are written as nothing", "", defaults.encode())
    }

    @Test
    fun `a choice back to the default is no longer written`() {
        val slot = Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)
        assertEquals("", defaults.with(slot, Action.NOTHING).with(slot, Action.HOME).encode())
    }

    @Test
    fun `what cannot be read is left out, the rest is kept`() {
        val read = JoystickBindings.decode("media.left.once=HOME;bogus;map.up.thrice=CONFIRM;map.down.once=NOT_AN_ACTION;map.center.once=CONFIRM")
        assertEquals(Action.HOME, read[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)])
        // The map mode's centre is locked (the dash's own); a choice saved for it does nothing. Declared.
        assertEquals(Action.NOTHING, read[Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)])
        assertEquals("a name it does not know is left out: down stays the info ring's", Action.INFO_RING, read[Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)])
        assertEquals(JoystickBindings.DEFAULT, JoystickBindings.decode(""))
    }

    @Test
    fun `a twice choice saved before is left out`() {
        val read = JoystickBindings.decode("media.center.twice=HOME;media.left.once=HOME")
        assertEquals("media.left.once=HOME", read.encode())
    }

    @Test
    fun `each action as Android Auto receives it`() {
        assertEquals(null, Action.NOTHING.command(assistant = true))
        assertEquals(AaCommand.Scroll(-1), Action.WHEEL_PREVIOUS.command(assistant = true))
        assertEquals(AaCommand.Scroll(1), Action.WHEEL_NEXT.command(assistant = true))
        assertEquals(AaCommand.Key(23), Action.CONFIRM.command(assistant = true))
        assertEquals(AaCommand.Key(4), Action.BACK.command(assistant = true))
        assertEquals(AaCommand.Key(3), Action.HOME.command(assistant = true))
        assertEquals(AaCommand.Key(85), Action.PLAY_PAUSE.command(assistant = true))
        assertEquals(AaCommand.Key(87), Action.NEXT_SONG.command(assistant = true))
        assertEquals(AaCommand.Key(88), Action.PREVIOUS_SONG.command(assistant = true))
        assertEquals(AaCommand.Key(84), Action.ASSISTANT.command(assistant = true))
        assertEquals("Gemini only with its switch (the microphone)", null, Action.ASSISTANT.command(assistant = false))
    }

    @Test
    fun `the dash's zoom label needs both zoom actions on the map mode`() {
        val both = JoystickBindings.DEFAULT
            .with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.ZOOM_OUT)
        assertEquals(true, both.zoomControls)
        assertEquals("none", false, JoystickBindings.DEFAULT.zoomControls)
        assertEquals("the page a new install starts with has both zooms", true, JoystickBindings.decode(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK).zoomControls)
        assertEquals("only zoom in", false, both.with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.NOTHING).zoomControls)
        assertEquals("only zoom out", false, both.with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.NOTHING).zoomControls)
        val media = JoystickBindings.DEFAULT
            .with(Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE), Action.ZOOM_OUT)
        assertEquals("the media mode's choices do not count", false, media.zoomControls)
        val ends = JoystickBindings.DEFAULT
            .with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.ZOOM_OUT)
        assertEquals("on any control that is not locked", true, ends.zoomControls)
        val onLocked = JoystickBindings.DEFAULT
            .with(Slot(Mode.MAP, Control.UP, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.ZOOM_OUT)
        assertEquals("a locked control does not count", false, onLocked.zoomControls)
        val onDown = JoystickBindings.DEFAULT
            .with(Slot(Mode.MAP, Control.DOWN, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.ZOOM_OUT)
        assertEquals("down is no longer locked: it counts", true, onDown.zoomControls)
    }

    @Test
    fun `the zoom actions are the picture's own, one step nearer or farther`() {
        assertEquals(AaCommand.PanelZoom(1), Action.ZOOM_IN.command(assistant = false))
        assertEquals(AaCommand.PanelZoom(-1), Action.ZOOM_OUT.command(assistant = false))
        val page = JoystickBindings.DEFAULT
            .with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.ZOOM_OUT)
        assertEquals("map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN", page.encode())
        assertEquals(page, JoystickBindings.decode(page.encode()))
    }

    // The map-mode choices saved under the older rules are dropped once.
    @Test
    fun `the map-mode choices can be dropped, the media ones kept`() {
        assertEquals("media.left.once=HOME", JoystickBindings.withoutMapChoices("media.left.once=HOME;map.left.once=ASSISTANT;map.up.hold=HOME"))
        assertEquals("", JoystickBindings.withoutMapChoices("map.right.once=ASSISTANT"))
        assertEquals("", JoystickBindings.withoutMapChoices(""))
    }

    /** The calls are built in: no control can be set to answer or end a call, and an old choice is left out. */
    @Test
    fun `answering and ending a call are not choices any more, and a saved one is left out`() {
        assertEquals(false, Action.entries.any { it.name == "ANSWER_CALL" || it.name == "END_CALL" })
        val page = JoystickBindings.decode("media.left.once=ANSWER_CALL;media.right.once=END_CALL;media.up.once=HOME")
        assertEquals("the unknown ones fall to their defaults", Action.WHEEL_PREVIOUS, page[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.WHEEL_NEXT, page[Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE)])
        assertEquals("the readable one is kept", Action.HOME, page[Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)])
    }

    /** The arrows are not choices any more: a saved one is left out, and the up control starts as nothing. */
    @Test
    fun `the arrows are gone, a saved one is left out and the media up starts as nothing`() {
        assertEquals(false, Action.entries.any { it.name.startsWith("ARROW_") })
        val page = JoystickBindings.decode("media.up.once=ARROW_UP;map.right.once=ARROW_RIGHT;media.center.once=HOME")
        assertEquals(Action.NOTHING, page[Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)])
        assertEquals(Action.WHEEL_NEXT, page[Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE)])
        assertEquals(Action.HOME, page[Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE)])
    }

    /** The owner's own page (28/10/2026) is what a new install starts with, whole: the media mode without a music control, zoom on the map's sides. */
    @Test
    fun `the page a new install starts with is the owner's own`() {
        assertEquals("media.up.once=HOME;media.down.once=ASSISTANT;map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN", com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)
        val page = JoystickBindings.decode(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)
        fun at(mode: Mode, control: Control) = page[Slot(mode, control, Gesture.ONCE)]
        assertEquals(Action.WHEEL_PREVIOUS, at(Mode.MEDIA, Control.LEFT))
        assertEquals(Action.WHEEL_NEXT, at(Mode.MEDIA, Control.RIGHT))
        assertEquals(Action.HOME, at(Mode.MEDIA, Control.UP))
        assertEquals(Action.ASSISTANT, at(Mode.MEDIA, Control.DOWN))
        assertEquals(Action.CONFIRM, at(Mode.MEDIA, Control.CENTER))
        assertEquals(Action.ZOOM_OUT, at(Mode.MAP, Control.LEFT))
        assertEquals(Action.ZOOM_IN, at(Mode.MAP, Control.RIGHT))
        assertEquals(Action.INFO_RING, at(Mode.MAP, Control.DOWN))
        assertEquals("up and centre of the map mode stay locked", listOf(Action.NOTHING, Action.NOTHING), listOf(at(Mode.MAP, Control.UP), at(Mode.MAP, Control.CENTER)))
        assertEquals("no music control in the media mode", false, page.mediaControls)
        assertEquals("both zooms on the map mode", true, page.zoomControls)
        assertEquals("the page reads back as written", com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK, page.encode())
    }
}
