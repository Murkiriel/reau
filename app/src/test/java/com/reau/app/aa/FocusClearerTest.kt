package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blue focus border can stay on Waze's map: Android Auto draws its focus once keys or the wheel come and only a
 * touch takes it away; a minute after the last joystick input, one touch, cancelled at once, does, once per quiet
 * spell.
 */
class FocusClearerTest {
    @Test
    fun `no tap before any joystick input`() {
        val focus = FocusClearer(idleMs = 60_000)
        assertFalse(focus.tapDue(nowMs = 1_000_000))
    }

    @Test
    fun `one tap a minute after the last input, and not again until the next`() {
        val focus = FocusClearer(idleMs = 60_000)
        focus.inputSent(nowMs = 10_000)
        assertFalse("59.9 s later", focus.tapDue(nowMs = 69_999))
        assertTrue("60 s later", focus.tapDue(nowMs = 70_000))
        assertFalse("once", focus.tapDue(nowMs = 200_000))
        focus.inputSent(nowMs = 300_000)
        assertTrue(focus.tapDue(nowMs = 360_500))
    }

    @Test
    fun `each input starts the minute again`() {
        val focus = FocusClearer(idleMs = 60_000)
        focus.inputSent(nowMs = 0)
        focus.inputSent(nowMs = 50_000)
        assertFalse(focus.tapDue(nowMs = 100_000))
        assertTrue(focus.tapDue(nowMs = 110_000))
    }

    @Test
    fun `a minute by default`() {
        assertEquals(60_000L, FocusClearer.IDLE_MS)
    }

    /**
     * The finger goes down in the middle of the picture and the touch is cancelled at once: going down is what ends
     * Android Auto's focus (Android's touch mode), and a cancelled touch is never a click nor a drag, wherever it lands.
     * So neither the density (the dpi may be changed) nor the side bar's side (it may be moved) matters.
     */
    @Test
    fun `the touch goes down in the middle and is cancelled`() {
        assertEquals(
            listOf(AaCommand.Touch(PhoneTouch.Action.DOWN, 400, 240), AaCommand.Touch(PhoneTouch.Action.CANCEL, 400, 240)),
            FocusClearer.TAP,
        )
    }

    @Test
    fun `the call keys are the phone's, not Android Auto's input`() {
        assertFalse(FocusClearer.toAndroidAuto(listOf(AaCommand.AnswerCall)))
        assertFalse(FocusClearer.toAndroidAuto(listOf(AaCommand.EndCall)))
        assertFalse(FocusClearer.toAndroidAuto(emptyList()))
        assertTrue(FocusClearer.toAndroidAuto(listOf(AaCommand.Scroll(1))))
        assertTrue(FocusClearer.toAndroidAuto(listOf(AaCommand.Key(23))))
        assertTrue(FocusClearer.toAndroidAuto(listOf(AaCommand.EndCall, AaCommand.Key(4))))
    }
}
