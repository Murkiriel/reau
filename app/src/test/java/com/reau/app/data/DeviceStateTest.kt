package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `device …` line every 30 s with the projection on and at once on a relevant change (screen, doze, charger,
 * thermal status, battery saver).
 */
class DeviceStateTest {
    private val full = DeviceState(
        batteryPct = 57, charging = true, batteryTempC = 31.5f, batteryMv = 4123,
        thermal = 1, headroom = 0.42f,
        appUsedMb = 45, appMaxMb = 512, phoneAvailMb = 1830, lowMemory = false,
        screenOn = true, interactive = true, dozing = false, powerSave = false, batteryExempt = false,
        bikeWifi = DeviceState.BikeWifi(dbm = -55, mhz = 5180, mbps = 433),
        data = DeviceState.Data.CELL, dataValidated = true,
    )

    @Test
    fun `the line says every part in a fixed order`() {
        assertEquals(
            "device battery=57% charging=yes temp=31.5C volt=4.123V thermal=light headroom=0.42 " +
                "mem=45/512MB phone=1830MB low=no screen=on interactive=yes doze=no saver=off exempt=no " +
                "bike=-55dBm/5180MHz/433Mbps data=cell/ok",
            full.line(),
        )
    }

    @Test
    fun `what could not be read is a question mark, the thermal headroom is left out, and no bike Wi-Fi is said`() {
        val unknown = DeviceState(
            batteryPct = null, charging = null, batteryTempC = null, batteryMv = null,
            thermal = null, headroom = null,
            appUsedMb = 45, appMaxMb = 512, phoneAvailMb = null, lowMemory = null,
            screenOn = null, interactive = null, dozing = null, powerSave = null, batteryExempt = null,
            bikeWifi = null, data = DeviceState.Data.NONE, dataValidated = false,
        )
        assertEquals(
            "device battery=? charging=? temp=? volt=? thermal=? " +
                "mem=45/512MB phone=? low=? screen=? interactive=? doze=? saver=? exempt=? bike=none data=none",
            unknown.line(),
        )
    }

    @Test
    fun `a headroom the phone gives is said, one that is not a number is left out`() {
        assertTrue(full.line(), "thermal=light headroom=0.42 mem=" in full.line())
        val nan = full.copy(headroom = Float.NaN).line()
        assertFalse(nan, "headroom" in nan)
        assertTrue(nan, "thermal=light mem=" in nan)
    }

    @Test
    fun `a data network not yet validated says so`() {
        assertTrue(full.copy(data = DeviceState.Data.WIFI, dataValidated = false).line().endsWith(" data=wifi/not-validated"))
    }

    @Test
    fun `every thermal status has its name`() {
        val names = (0..6).map { full.copy(thermal = it).line().substringAfter("thermal=").substringBefore(' ') }
        assertEquals(listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown"), names)
    }

    @Test
    fun `only screen, doze, charger, thermal status and saver are relevant changes`() {
        assertEquals(emptyList<String>(), full.relevantChangesFrom(full.copy(batteryPct = 12, batteryTempC = 40f, appUsedMb = 300)))
        assertEquals(
            listOf("screen", "doze", "charging", "thermal", "saver"),
            full.relevantChangesFrom(full.copy(screenOn = false, dozing = true, charging = false, thermal = 3, powerSave = true)),
        )
    }

    private class Lines {
        val written = ArrayList<String>()
        val log = DeviceStateLog(periodMs = 30_000) { written += it }
    }

    @Test
    fun `the first look writes at once, saying it is the start`() {
        val l = Lines()
        l.log.offer(0, full)
        assertEquals(listOf(full.line() + " [start]"), l.written)
    }

    @Test
    fun `nothing relevant changed, one line every 30 s`() {
        val l = Lines()
        l.log.offer(0, full)
        l.log.offer(5_000, full.copy(batteryPct = 56))
        l.log.offer(29_999, full)
        assertEquals(1, l.written.size)
        l.log.offer(30_000, full.copy(batteryPct = 55))
        assertEquals(2, l.written.size)
        assertTrue(l.written[1], l.written[1].startsWith("device battery=55%") && l.written[1].endsWith(" [30 s]"))
    }

    @Test
    fun `a relevant change writes at once, says what changed, and the 30 s start again from it`() {
        val l = Lines()
        l.log.offer(0, full)
        l.log.offer(5_000, full.copy(screenOn = false, charging = false))
        assertEquals(2, l.written.size)
        assertTrue(l.written[1], l.written[1].endsWith(" [change: screen, charging]"))
        l.log.offer(30_000, full.copy(screenOn = false, charging = false))
        assertEquals("not 30 s since the change", 2, l.written.size)
        l.log.offer(35_000, full.copy(screenOn = false, charging = false))
        assertEquals(3, l.written.size)
    }

    @Test
    fun `a change is compared with the last look, not with the last line`() {
        val l = Lines()
        l.log.offer(0, full)
        l.log.offer(5_000, full.copy(screenOn = false))
        l.log.offer(10_000, full.copy(screenOn = false))
        assertEquals("no change from the look before", 2, l.written.size)
    }
}
