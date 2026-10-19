package com.reau.app.ui.components

import com.reau.app.ui.components.StatusKind.CONNECTING
import com.reau.app.ui.components.StatusKind.ERROR
import com.reau.app.ui.components.StatusKind.IDLE
import com.reau.app.ui.components.StatusKind.OK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The design's components: their rules without a screen. */
class ComponentLogicTest {
    @Test
    fun `the bridge line shows a problem first, then a side connecting, then both ready`() {
        assertEquals(ERROR, ComponentLogic.bridgeKind(ERROR, OK))
        assertEquals(ERROR, ComponentLogic.bridgeKind(CONNECTING, ERROR))
        assertEquals(CONNECTING, ComponentLogic.bridgeKind(OK, CONNECTING))
        assertEquals(CONNECTING, ComponentLogic.bridgeKind(CONNECTING, IDLE))
        assertEquals(OK, ComponentLogic.bridgeKind(OK, OK))
        assertEquals(IDLE, ComponentLogic.bridgeKind(OK, IDLE))
        assertEquals(IDLE, ComponentLogic.bridgeKind(IDLE, IDLE))
    }

    @Test
    fun `a counter steps by one and by ten and stays inside its range`() {
        assertEquals(71, ComponentLogic.stepped(70, 1, 0..100))
        assertEquals(60, ComponentLogic.stepped(70, -10, 0..100))
        assertEquals(100, ComponentLogic.stepped(95, 10, 0..100))
        assertEquals(0, ComponentLogic.stepped(4, -10, 0..100))
        assertEquals(12, ComponentLogic.stepped(12, -1, 12..30))
    }

    @Test
    fun `removed animations stop the spinning and the sliding`() {
        assertFalse(ComponentLogic.animationsOn(0f))
        assertTrue(ComponentLogic.animationsOn(1f))
        assertTrue(ComponentLogic.animationsOn(0.5f))
    }

    @Test
    fun `the time since the start reads minutes and seconds`() {
        assertEquals("00:00", ComponentLogic.elapsed(0))
        assertEquals("00:59", ComponentLogic.elapsed(59_999))
        assertEquals("04:12", ComponentLogic.elapsed(252_000))
        assertEquals("75:03", ComponentLogic.elapsed(4_503_000))
        assertEquals("00:00", ComponentLogic.elapsed(-5_000))
    }
}
