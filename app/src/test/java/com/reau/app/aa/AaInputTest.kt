package com.reau.app.aa

import com.reau.app.aa.proto.Input
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dash's joystick driving Android Auto: keys and the
 * rotary wheel go to Android Auto over its input channel, and each dash button becomes one of them.
 */
class AaInputTest {
    private val sent = mutableListOf<AapMessage>()
    private val input = AaInput(send = { sent += it }, clockUs = { 1_000L }, log = {})

    private fun report(m: AapMessage): Input.InputReport {
        assertEquals("the input channel", Channel.ID_INP, m.channel)
        assertEquals("an input event", Input.MsgType.EVENT_VALUE, m.type)
        return Input.InputReport.parseFrom(m.data.copyOfRange(m.dataOffset, m.size)) // size counts the header too
    }

    @Test
    fun `a key is a press and a release`() {
        input.sendKey(ServiceDiscoveryResponse.KEY_ENTER)
        assertEquals(2, sent.size)
        val down = report(sent[0]).keyEvent.getKeys(0)
        val up = report(sent[1]).keyEvent.getKeys(0)
        assertEquals(ServiceDiscoveryResponse.KEY_ENTER, down.keycode)
        assertTrue(down.down)
        assertEquals(ServiceDiscoveryResponse.KEY_ENTER, up.keycode)
        assertEquals(false, up.down)
    }

    @Test
    fun `a wheel step is a relative event of the scroll wheel`() {
        input.sendScroll(-1)
        val rel = report(sent.single()).relativeEvent.getData(0)
        assertEquals(ServiceDiscoveryResponse.KEY_SCROLL_WHEEL, rel.keycode)
        assertEquals(-1, rel.delta)
    }

    @Test
    fun `a touch from the phone is a touch event with one finger where it was`() {
        // Android Auto used from the phone's screen (behind a switch). One finger.
        input.sendTouch(PhoneTouch.Action.DOWN, 120, 340)
        input.sendTouch(PhoneTouch.Action.MOVE, 130, 345)
        input.sendTouch(PhoneTouch.Action.UP, 131, 346)
        val events = sent.map { report(it).touchEvent }
        assertEquals(
            listOf(Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN, Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE, Input.TouchEvent.PointerAction.TOUCH_ACTION_UP),
            events.map { it.action },
        )
        assertEquals(listOf(120 to 340, 130 to 345, 131 to 346), events.map { it.getPointerData(0).x to it.getPointerData(0).y })
        assertTrue("one finger, always pointer 0", events.all { it.pointerDataCount == 1 && it.getPointerData(0).pointerId == 0 && it.actionIndex == 0 })
    }

    /** A touch can be cancelled, as Android's own (TOUCH_ACTION_CANCEL = MotionEvent.ACTION_CANCEL). */
    @Test
    fun `a cancelled touch is a touch event with the cancel action`() {
        input.sendTouch(PhoneTouch.Action.DOWN, 400, 240)
        input.sendTouch(PhoneTouch.Action.CANCEL, 400, 240)
        assertEquals(
            listOf(Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN, Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL),
            sent.map { report(it).touchEvent.action },
        )
    }

    @Test
    fun `Android Auto is told of the home and assistant keys too`() {
        assertTrue(ServiceDiscoveryResponse.KEY_HOME in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertTrue(ServiceDiscoveryResponse.KEY_ASSISTANT in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
    }

    // The dash's buttons moved to JoystickGesturesTest and JoystickBindingsTest with the joystick page: the rules
    // changed, so the old tests here were replaced, not kept.
}
