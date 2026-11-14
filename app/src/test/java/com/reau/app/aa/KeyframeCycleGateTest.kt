package com.reau.app.aa

import com.reau.app.aa.KeyframeCycleGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for cycling video focus to get a keyframe from Android Auto: a second apart, no more than three in 30 s
 * before a 15 s wait, none while the compositor is stuck, and a request that must wait is kept, not lost.
 */
class KeyframeCycleGateTest {
    private val gate = KeyframeCycleGate()

    @Test
    fun `the first request goes out`() {
        assertEquals(Verdict.GO, gate.request(10_000L))
        assertFalse(gate.isPending)
    }

    @Test
    fun `a second request within a second waits and is kept, and the tick lets it out after the second`() {
        assertEquals(Verdict.GO, gate.request(10_000L))
        assertEquals(Verdict.WAIT_SPACING, gate.request(10_500L))
        assertTrue(gate.isPending)
        assertFalse("not yet", gate.takePending(10_900L))
        assertFalse("a second exactly is still too soon, as in VideoRecoveryPolicy", gate.takePending(11_000L))
        assertTrue(gate.takePending(11_100L))
        assertFalse("and it is not pending any more", gate.isPending)
    }

    @Test
    fun `a request that waited goes out once, never twice`() {
        gate.request(10_000L)
        gate.request(10_200L)
        gate.request(10_300L) // two waiting make one cycle
        assertTrue(gate.takePending(11_100L))
        assertFalse(gate.takePending(11_200L))
        assertFalse(gate.takePending(60_000L))
    }

    @Test
    fun `with nothing pending the tick lets nothing out`() {
        gate.request(10_000L)
        assertFalse(gate.takePending(20_000L))
    }

    @Test
    fun `the fourth cycle in 20 seconds waits fifteen seconds after the third, then goes out by the tick`() {
        assertEquals(Verdict.GO, gate.request(0L))
        assertEquals(Verdict.GO, gate.request(2_000L))
        assertEquals(Verdict.GO, gate.request(4_000L))
        assertEquals("the storm guard", Verdict.WAIT_STORM, gate.request(6_000L))
        assertEquals(13_000L, gate.waitLeftMs(6_000L))
        assertFalse(gate.takePending(18_999L))
        assertTrue(gate.takePending(19_000L))
    }

    @Test
    fun `cycles spread over more than thirty seconds are no storm`() {
        assertEquals(Verdict.GO, gate.request(0L))
        assertEquals(Verdict.GO, gate.request(12_000L))
        assertEquals(Verdict.GO, gate.request(24_000L))
        assertEquals("the first is thirty-one seconds old", Verdict.GO, gate.request(31_000L))
        assertEquals(Verdict.GO, gate.request(43_000L))
    }

    @Test
    fun `a storm that goes on still lets cycles out, the guard waits and does not shut for good`() {
        assertEquals(Verdict.GO, gate.request(0L))
        assertEquals(Verdict.GO, gate.request(2_000L))
        assertEquals(Verdict.GO, gate.request(4_000L))
        assertEquals(Verdict.WAIT_STORM, gate.request(10_000L))
        assertTrue(gate.takePending(19_000L))
        assertEquals(Verdict.WAIT_STORM, gate.request(25_000L))
        assertFalse("three cycles still inside the last thirty seconds", gate.takePending(31_999L))
        assertTrue("the first of them is thirty seconds old", gate.takePending(32_000L))
    }

    @Test
    fun `a clock gone back lets nothing out`() {
        assertEquals(Verdict.GO, gate.request(50_000L))
        assertEquals(Verdict.WAIT_SPACING, gate.request(40_000L))
        assertFalse(gate.takePending(45_000L))
    }

    @Test
    fun `with the compositor stuck nothing goes out and the request stays until it is free`() {
        assertEquals(Verdict.WAIT_BLOCKED, gate.request(10_000L, blocked = true))
        assertTrue(gate.isPending)
        assertFalse(gate.takePending(20_000L, blocked = true))
        assertFalse(gate.takePending(30_000L, blocked = true))
        assertTrue("free again: it goes out at the next tick", gate.takePending(31_000L, blocked = false))
        assertFalse(gate.isPending)
    }

    @Test
    fun `a cycle held back by the compositor does not count towards the storm`() {
        repeat(5) { assertEquals(Verdict.WAIT_BLOCKED, gate.request(1_000L * it, blocked = true)) }
        assertEquals(Verdict.GO, gate.request(6_000L))
    }

    @Test
    fun `the wait left is zero when the rules of time allow a cycle`() {
        assertEquals(0L, gate.waitLeftMs(1_000L))
        gate.request(10_000L)
        assertEquals(1_000L, gate.waitLeftMs(10_000L))
        assertEquals(0L, gate.waitLeftMs(11_000L))
    }
}
