package com.reau.app.service

import android.content.Intent
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `system …` lines for what the phone does around REau
 * (screen, doze, saver, charger, low battery, the networks, the app going to the background and back).
 */
class SystemEventsTest {
    @Test
    fun `each broadcast has its line`() {
        val lines = listOf(
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY,
        ).map { SystemEvents.describe(it, dozing = null, saver = null) }
        assertEquals(listOf("screen on", "screen off", "charger in", "charger out", "battery low", "battery okay"), lines)
    }

    @Test
    fun `doze and saver say which way they went`() {
        assertEquals("doze on", SystemEvents.describe(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, dozing = true, saver = null))
        assertEquals("doze off", SystemEvents.describe(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, dozing = false, saver = null))
        assertEquals("saver on", SystemEvents.describe(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, dozing = null, saver = true))
        assertEquals("saver off", SystemEvents.describe(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, dozing = null, saver = false))
        assertEquals("doze ?", SystemEvents.describe(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, dozing = null, saver = null))
    }

    @Test
    fun `a doze or saver broadcast that finds the state it left says it came late`() {
        val t = SystemEvents.Toggles()
        assertEquals("doze on", t.doze(true))
        assertEquals("doze on (came late: it changed and came back before REau heard)", t.doze(true))
        assertEquals("doze off", t.doze(false))
        assertEquals("saver off", t.saver(false))
        assertEquals("saver off (came late: it changed and came back before REau heard)", t.saver(false))
        assertEquals("doze ?", t.doze(null))
        assertEquals("doze ?", t.doze(null))
    }

    /**
     * Once "battery low" came dozens of times in one ride at 51-60%. Android's low-battery mark follows the battery saver's
     * automatic level when the rider set one (here about 60%), and the phone repeats the broadcast at each 1% it drops.
     * One line per stretch below the mark, with the level and the mark; again only after the charger or "okay".
     */
    @Test
    fun `a low battery is said once, with the level and the saver's mark, until it charges or comes back`() {
        val b = SystemEvents.LowBattery()
        assertEquals(
            "battery low at 57% (Android's low mark follows the battery saver's automatic level: 60%; said once until it charges or comes back)",
            b.low(level = 57, saverAt = 60),
        )
        assertNull("the repeat at each 1% says nothing", b.low(level = 56, saverAt = 60))
        b.charger()
        assertEquals("battery low at 55% (said once until it charges or comes back)", b.low(level = 55, saverAt = 0))
        assertNull(b.low(level = 54, saverAt = 0))
        assertEquals("battery okay", b.okay())
        assertEquals("battery low at ?%", b.low(level = null, saverAt = null))
    }

    @Test
    fun `a broadcast not listened for has no line`() {
        assertNull(SystemEvents.describe(Intent.ACTION_TIME_TICK, dozing = null, saver = null))
    }

    @Test
    fun `the networks, available and lost`() {
        assertEquals("data network available: cell", SystemEvents.dataNetwork("cell"))
        assertEquals("data network lost", SystemEvents.dataNetwork(null))
        assertEquals("bike network available", SystemEvents.bikeNetwork(true))
        assertEquals("bike network lost", SystemEvents.bikeNetwork(false))
    }

    @Test
    fun `the app goes to the background when its last screen stops, and comes back with the first`() {
        val said = ArrayList<String>()
        val v = AppVisibility { said += it }
        v.started()
        v.started() // a second screen over the first
        v.stopped()
        assertEquals("one screen still shown", listOf("app foreground"), said)
        v.stopped()
        v.started()
        assertEquals(listOf("app foreground", "app background", "app foreground"), said)
    }

    /** The background line says whether the projection still holds the foreground (its network depends on it). */
    @Test
    fun `the background line says how the process stands`() {
        val said = ArrayList<String>()
        val v = AppVisibility(standing = { "process importance 125 (foreground service), projection in the foreground" }) { said += it }
        v.started()
        v.stopped()
        assertEquals(
            listOf("app foreground", "app background — process importance 125 (foreground service), projection in the foreground"),
            said,
        )
    }

    @Test
    fun `a stop with nothing started says nothing`() {
        val said = ArrayList<String>()
        val v = AppVisibility { said += it }
        v.stopped()
        v.started()
        assertEquals(listOf("app foreground"), said)
    }
}
