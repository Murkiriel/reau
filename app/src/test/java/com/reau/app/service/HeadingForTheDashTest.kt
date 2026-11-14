package com.reau.app.service

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.weather.GpsReading
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The heading in 05 2D: the last GPS fix's, only with the phone moving at 1 m/s or more and a fix of at most 10 s, and only with "Usar a localização"
 * on; with no fix, none of those, or the GPS not listened to, it is the zero it always was. The GPS is the info ring's: nothing here turns it on.
 */
class HeadingForTheDashTest {
    private var now = 1_000L
    private val watcher = GpsWatcher { now }

    private fun listening() {
        watcher.want(true, { true }) { { } }
        watcher.satellitesSeen(7, 19)
    }

    private fun heading() = PhoneStatusReader.headingOf(watcher.read())

    @Test
    fun `a moving fix gives its heading in whole degrees`() {
        listening()
        watcher.fixSeen(5f, 270.4f, 12f)
        assertEquals(270, heading())
        watcher.fixSeen(5f, 359.6f, 3f)
        assertEquals("359.6 rounds to 360, which is north again", 0, heading())
        watcher.fixSeen(5f, -10f, 3f)
        assertEquals("a negative heading is wrapped into 0..359", 350, heading())
    }

    @Test
    fun `the limit is 1 m per second`() {
        listening()
        watcher.fixSeen(5f, 90f, 0.99f)
        assertEquals(DashCommands.BEARING_NONE, heading())
        watcher.fixSeen(5f, 90f, 1.0f)
        assertEquals(90, heading())
    }

    @Test
    fun `a fix without a heading or a speed says no heading`() {
        listening()
        watcher.fixSeen(5f, null, 12f)
        assertEquals(DashCommands.BEARING_NONE, heading())
        watcher.fixSeen(5f, 90f, null)
        assertEquals(DashCommands.BEARING_NONE, heading())
    }

    @Test
    fun `a fix of more than 10 seconds says no heading, one of exactly 10 still does`() {
        listening()
        watcher.fixSeen(5f, 90f, 12f)
        now += 10_000L
        watcher.satellitesSeen(7, 19)
        assertEquals(90, heading())
        now += 1L
        watcher.satellitesSeen(7, 19)
        assertEquals(DashCommands.BEARING_NONE, heading())
    }

    @Test
    fun `no fix at all, or the GPS not listened to, says no heading and never asks for the GPS`() {
        assertEquals("not listened to", DashCommands.BEARING_NONE, heading())
        listening()
        assertEquals("satellites but no fix yet", DashCommands.BEARING_NONE, heading())
        assertEquals(DashCommands.BEARING_NONE, PhoneStatusReader.headingOf(null))
        watcher.fixSeen(5f, 90f, 12f)
        watcher.stop()
        assertEquals("stopped: forgotten", DashCommands.BEARING_NONE, heading())
    }

    @Test
    fun `the heading goes only with the location switch on`() {
        val status = DashCommands.PhoneStatus(80, false, true, true, bearingDeg = 120)
        assertEquals(120, PhoneStatusReader.withLocationRule(status, useLocation = true).bearingDeg)
        assertEquals(DashCommands.BEARING_NONE, PhoneStatusReader.withLocationRule(status, useLocation = false).bearingDeg)
        // Everything else of the status is left alone.
        assertEquals(status.copy(bearingDeg = DashCommands.BEARING_NONE), PhoneStatusReader.withLocationRule(status, false))
        val none = status.copy(bearingDeg = DashCommands.BEARING_NONE)
        assertEquals(none, PhoneStatusReader.withLocationRule(none, false))
    }

    @Test
    fun `the reading of the GPS is what the rule reads`() {
        assertEquals(45, PhoneStatusReader.headingOf(GpsReading(5, 10, 8f, 45f, 2f)))
    }
}
