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

    @Test
    fun `the time on Desligar shows only while running with a known start, and counts from that start`() {
        assertEquals("04:12", ComponentLogic.elapsedSince(running = true, startedAtMs = 1_000, nowMs = 253_000))
        assertEquals("a stopped projection shows no time", null, ComponentLogic.elapsedSince(running = false, startedAtMs = 1_000, nowMs = 253_000))
        assertEquals("no known start shows none", null, ComponentLogic.elapsedSince(running = true, startedAtMs = null, nowMs = 253_000))
        assertEquals("a start after the clock reads zero, never a negative time", "00:00", ComponentLogic.elapsedSince(true, 9_000, 1_000))
    }

    @Test
    fun `segments go on one line while the widest label fits an equal share`() {
        // 328 px wide, 6 px gaps, four labels of up to 70 px: (328 + 6) / 76 = 4 fit.
        assertEquals(4, ComponentLogic.segmentColumns(4, 70, 328, 6))
        assertEquals(3, ComponentLogic.segmentColumns(3, 100, 328, 6))
        // The edge: four of 76 px and three gaps take exactly 4 * 76 + 3 * 6 = 322 px: 322 holds them, 321 does not.
        assertEquals(4, ComponentLogic.segmentColumns(4, 76, 322, 6))
        assertEquals(2, ComponentLogic.segmentColumns(4, 76, 321, 6))
    }

    @Test
    fun `a label that does not fit moves the segments to more lines, never cut, the lines kept even`() {
        // Four languages at a large font: the widest wants 120 px of 328 -> 2 fit -> 2 + 2.
        assertEquals(2, ComponentLogic.segmentColumns(4, 120, 328, 6))
        // Four where 3 would fit: still 2 + 2, not 3 + 1.
        assertEquals(2, ComponentLogic.segmentColumns(4, 100, 328, 6))
        // Five where 4 would fit: 3 + 2, not 4 + 1.
        assertEquals(3, ComponentLogic.segmentColumns(5, 76, 328, 6))
        // Three where 2 fit: 2 + 1.
        assertEquals(2, ComponentLogic.segmentColumns(3, 150, 328, 6))
    }

    @Test
    fun `segments never go below one column nor above their count`() {
        assertEquals("a label wider than the row still takes a line of its own", 1, ComponentLogic.segmentColumns(3, 500, 328, 6))
        assertEquals(1, ComponentLogic.segmentColumns(1, 50, 328, 6))
        assertEquals(1, ComponentLogic.segmentColumns(0, 50, 328, 6))
        assertEquals("narrow labels do not make more columns than segments", 2, ComponentLogic.segmentColumns(2, 10, 328, 6))
        assertEquals(1, ComponentLogic.segmentColumns(3, 100, 0, 6))
    }
}
