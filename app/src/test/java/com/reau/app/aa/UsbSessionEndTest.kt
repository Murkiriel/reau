package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android Auto closes its sessions at every change of the USB (ride of 11/11/2026: the end came 15 to 50 ms after the `usb` line).
 * Such an end is said in the log, and only it is dialled again at once; any other end waits as before. The clock is a counter.
 */
class UsbSessionEndTest {
    private var now = 10_000L
    private val usb = UsbSessionEnd({ now })

    @Test
    fun `an end 47 ms after a USB change is said and the next dial waits no time`() {
        usb.changed()
        now += 47
        val ending = usb.explain()
        assertEquals(
            "AaReceiver: the Android Auto session ended 47 ms after the USB changed (Android Auto closes its sessions on every USB change)",
            ending.line,
        )
        assertEquals(0L, ending.waitAfterDropMs(1_000))
    }

    @Test
    fun `with no recent USB change the end is not said and the wait is the normal one`() {
        val none = usb.explain()
        assertNull(none.line)
        assertEquals(1_000L, none.waitAfterDropMs(1_000))
        usb.changed()
        now += 1_500
        val late = usb.explain()
        assertNull("the change was 1.5 s ago", late.line)
        assertEquals(1_000L, late.waitAfterDropMs(1_000))
    }

    @Test
    fun `the window is a second, edge included`() {
        usb.changed()
        now += UsbSessionEnd.WINDOW_MS
        assertEquals(0L, usb.explain().waitAfterDropMs(1_000))
        usb.changed()
        now += UsbSessionEnd.WINDOW_MS + 1
        assertEquals(1_000L, usb.explain().waitAfterDropMs(1_000))
    }

    @Test
    fun `one change explains one end, not the next`() {
        usb.changed()
        now += 20
        assertEquals(0L, usb.explain().waitAfterDropMs(1_000))
        now += 100
        val second = usb.explain()
        assertNull(second.line)
        assertEquals(1_000L, second.waitAfterDropMs(1_000))
    }

    @Test
    fun `a later change counts from its own instant`() {
        usb.changed()
        now += 900
        usb.changed()
        now += 900
        val ending = usb.explain()
        assertEquals(900L, ending.sinceMs)
    }

    @Test
    fun `a clock that went back does not make an end a USB one`() {
        usb.changed()
        now -= 5
        assertNull(usb.explain().line)
    }

    @Test
    fun `the end after a USB change is written to the log, and the pause before the next dial is none`() {
        val said = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        usb.changed()
        now += 15
        usb.noteEnd { said += it }.pauseAfterDrop(1_000) { slept += it }
        assertEquals(listOf(UsbSessionEnd.line(15)), said)
        assertEquals("no pause at all: the dial goes out at once", emptyList<Long>(), slept)
    }

    @Test
    fun `any other end writes nothing and pauses the second it always paused`() {
        val said = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        usb.noteEnd { said += it }.pauseAfterDrop(1_000) { slept += it }
        assertEquals(emptyList<String>(), said)
        assertEquals(listOf(1_000L), slept)
    }

    @Test
    fun `after the quick dial the pace is the usual one`() {
        val slept = mutableListOf<Long>()
        usb.changed()
        now += 30
        usb.noteEnd { }.pauseAfterDrop(1_000) { slept += it }
        // The next end, a moment later and with no new USB change, waits as always.
        now += 300
        usb.noteEnd { }.pauseAfterDrop(1_000) { slept += it }
        assertEquals(listOf(1_000L), slept)
    }
}
