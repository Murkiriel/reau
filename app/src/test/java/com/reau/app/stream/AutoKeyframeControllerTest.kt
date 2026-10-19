package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoKeyframeControllerTest {

    private fun AutoKeyframeController.minute(
        sent: Double = 12.0,
        decoded: Double? = 12.0,
        kbps: Double = 300.0,
        setKbps: Double = 1024.0,
        allActive: Boolean = true,
    ): AutoKeyframeController.Decision {
        var last: AutoKeyframeController.Decision? = null
        repeat(AutoKeyframeController.WINDOWS_PER_DECISION) {
            last = onWindow(AutoKeyframeController.Window(sent, decoded, kbps, setKbps, allActive))
        }
        return last!!
    }

    @Test
    fun `starts at 1 second`() {
        assertEquals(1, AutoKeyframeController().currentSec)
    }

    @Test
    fun `no decision before a full minute of moving windows`() {
        val c = AutoKeyframeController()
        repeat(AutoKeyframeController.WINDOWS_PER_DECISION - 1) {
            assertFalse(c.onWindow(AutoKeyframeController.Window(12.0, 12.0, 900.0, 1024.0, true)).changed)
        }
        assertEquals(1, c.currentSec)
    }

    @Test
    fun `a busy encoder moves to 2 seconds`() {
        val c = AutoKeyframeController()
        val d = c.minute(kbps = 800.0) // 78% of 1024
        assertTrue(d.changed)
        assertEquals(2, d.intervalSec)
    }

    @Test
    fun `any dash shortfall in the minute moves back to 1 second, even when busy`() {
        val c = AutoKeyframeController()
        c.minute(kbps = 800.0)
        assertEquals(2, c.currentSec)
        var last: AutoKeyframeController.Decision? = null
        repeat(AutoKeyframeController.WINDOWS_PER_DECISION) { i ->
            last = c.onWindow(AutoKeyframeController.Window(12.0, if (i == 3) 10.0 else 12.0, 800.0, 1024.0, true))
        }
        assertTrue(last!!.changed)
        assertEquals(1, last!!.intervalSec)
    }

    @Test
    fun `a quiet encoder with a clean dash keeps the current interval`() {
        val c = AutoKeyframeController()
        assertFalse(c.minute(kbps = 300.0).changed)
        assertEquals(1, c.currentSec)
        c.minute(kbps = 800.0)
        assertFalse(c.minute(kbps = 300.0).changed)
        assertEquals(2, c.currentSec)
    }

    @Test
    fun `idle windows do not count toward the minute`() {
        val c = AutoKeyframeController()
        repeat(100) { c.onWindow(AutoKeyframeController.Window(4.0, 4.0, 900.0, 1024.0, allActive = false)) }
        assertEquals(1, c.currentSec)
    }

    @Test
    fun `without dash acks only the bitrate rule applies`() {
        val c = AutoKeyframeController()
        assertEquals(2, c.minute(decoded = null, kbps = 800.0).intervalSec)
    }

    @Test
    fun `the decision reason says what drove it`() {
        val c = AutoKeyframeController()
        assertTrue(c.minute(kbps = 800.0).reason.contains("kbps"))
    }
}
