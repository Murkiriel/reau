package com.reau.app.data

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** What the line that says a link ended knows of the phone. */
class PhoneNowTest {
    @Before
    fun before() = PhoneNow.testOnlyReset()

    @After
    fun after() = PhoneNow.testOnlyReset()

    @Test
    fun `nothing heard yet is a question mark for each part`() {
        assertEquals("charger ?, screen ?, doze ?", PhoneNow.text())
    }

    @Test
    fun `the phone's broadcasts say the screen, the charger and doze`() {
        PhoneNow.heard(Intent.ACTION_POWER_CONNECTED, dozing = false)
        PhoneNow.heard(Intent.ACTION_SCREEN_ON, dozing = false, nowMs = 1_000)
        assertEquals("charger in, screen on, doze off, screen event 0.0 s before", PhoneNow.text(nowMs = 1_000))
        PhoneNow.heard(Intent.ACTION_POWER_DISCONNECTED, dozing = false)
        PhoneNow.heard(Intent.ACTION_SCREEN_OFF, dozing = true, nowMs = 2_000)
        assertEquals("charger out, screen off, doze on, screen event 0.5 s before", PhoneNow.text(nowMs = 2_500))
    }

    @Test
    fun `the unlock is a screen event too, and a charger or doze broadcast is not`() {
        PhoneNow.heard(Intent.ACTION_USER_PRESENT, dozing = null, nowMs = 5_000)
        assertEquals("charger ?, screen ?, doze ?, screen event 2.3 s before", PhoneNow.text(nowMs = 7_300))
        PhoneNow.testOnlyReset()
        PhoneNow.heard(Intent.ACTION_POWER_CONNECTED, dozing = false, nowMs = 5_000)
        assertEquals("charger in, screen ?, doze off", PhoneNow.text(nowMs = 7_300))
    }

    @Test
    fun `a broadcast about something else keeps what was known, and a part not read keeps its last word`() {
        PhoneNow.update(charging = true, screenOn = true, dozing = false)
        PhoneNow.heard(Intent.ACTION_BATTERY_LOW, dozing = null)
        assertEquals("charger in, screen on, doze off", PhoneNow.text())
        PhoneNow.update(charging = null, screenOn = false, dozing = null)
        assertEquals("charger in, screen off, doze off", PhoneNow.text())
    }

    @Test
    fun `the battery temperature of the device read is kept, and a null part leaves what was known`() {
        assertEquals(null, PhoneNow.batteryTempNow(nowMs = 0))
        PhoneNow.update(charging = true, screenOn = true, dozing = false, batteryTempC = 44.5f, nowMs = 1_000)
        assertEquals(44.5f, PhoneNow.batteryTempNow(nowMs = 1_000)!!, 0f)
        PhoneNow.update(charging = null, screenOn = null, dozing = null, batteryTempC = null, nowMs = 5_000)
        assertEquals("a read with no temperature leaves the last", 44.5f, PhoneNow.batteryTempNow(nowMs = 5_000)!!, 0f)
        PhoneNow.update(charging = null, screenOn = null, dozing = null, batteryTempC = 41f, nowMs = 6_000)
        assertEquals(41f, PhoneNow.batteryTempNow(nowMs = 6_000)!!, 0f)
    }

    @Test
    fun `a temperature older than 30 seconds belongs to a projection that has ended`() {
        PhoneNow.update(null, null, null, batteryTempC = 46f, nowMs = 10_000)
        assertEquals(46f, PhoneNow.batteryTempNow(nowMs = 10_000 + PhoneNow.TEMP_FRESH_MS)!!, 0f)
        assertEquals(null, PhoneNow.batteryTempNow(nowMs = 10_000 + PhoneNow.TEMP_FRESH_MS + 1))
        assertEquals(30_000L, PhoneNow.TEMP_FRESH_MS)
    }

    @Test
    fun `the device read feeds the temperature to it`() {
        val reader = java.io.File("src/main/java/com/reau/app/service/DeviceStateReader.kt").readText()
        assertEquals(true, reader.contains("PhoneNow.update(it.charging, it.screenOn, it.dozing, it.batteryTempC)"))
    }
}
