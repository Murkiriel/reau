package com.reau.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy: a switch "Usar a localização", off by default. Off, REau reads no position (the night mode goes by the time
 * zone). The Wi-Fi list, to find the dash's whole name, goes by the location permission alone (on Android 14 the
 * nearby-devices permission gave an empty list).
 */
class LocationUseTest {
    @Test
    fun `the switch is off until switched on, and kept when saved`() {
        assertEquals(false, REauSettings().useLocation)
        val prefs = com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(useLocation = true))
        assertEquals(true, SettingsStore(prefs).load().useLocation)
    }

    @Test
    fun `the Wi-Fi list is read with the location permission, whatever the switch`() {
        assertTrue(LocationUse.mayScan(locationGranted = true))
        assertFalse(LocationUse.mayScan(locationGranted = false))
    }
}
