package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Four settings are no longer choices. Android Auto is asked for 800×480; the video clock runs at 0% slower; the
 * instrument cluster experiment is gone; and touching Android Auto from the phone is native. Whatever a phone has
 * stored from before, these are what is loaded. The frame size and the cluster left the code: what a phone stored for
 * them is dropped at the next save.
 */
class FixedSettingsTest {
    @Test
    fun `the defaults are the fixed values`() {
        val s = REauSettings()
        assertEquals(0, s.clockSlowPercent)
        assertEquals(true, s.phoneTouch)
        assertEquals(true, s.aaConfig.touch)
    }

    @Test
    fun `what a phone stored before is read as the fixed values`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("clock_slow_percent", 5).putBoolean("phone_touch", false).apply()
        val s = SettingsStore(prefs).load()
        assertEquals(0, s.clockSlowPercent)
        assertEquals(true, s.phoneTouch)
    }

    @Test
    fun `a value set in code is brought back to the fixed one`() {
        val s = REauSettings(clockSlowPercent = 8, phoneTouch = false).sanitized()
        assertEquals(0, s.clockSlowPercent)
        assertEquals(true, s.phoneTouch)
    }

    @Test
    fun `the frame size and the cluster a phone stored are dropped at the next save`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putString("resolution", "LANDSCAPE_1280x720").putString("cluster", "B").apply()
        SettingsStore(prefs).save(SettingsStore(prefs).load())
        assertFalse(prefs.contains("resolution"))
        assertFalse(prefs.contains("cluster"))
    }
}
