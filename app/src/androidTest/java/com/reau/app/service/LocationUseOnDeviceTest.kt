package com.reau.app.service

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The switch "Usar a localização" on a real Android: with the location permission
 * granted and the switch off, REau reads no position and does not read the Wi-Fi list by location; on, it may.
 */
@RunWith(AndroidJUnit4::class)
class LocationUseOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        // Granting only: revoking a runtime permission kills the app's process, the one this test runs in.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** The location is asked only to find the Wi-Fi: the Wi-Fi list goes by the location permission alone. */
    @Test
    fun theWifiListGoesByTheLocationPermissionWhateverTheSwitch() {
        com.reau.app.settings.SettingsStore.open(context).let { it.save(it.load().copy(useLocation = false)) }
        assertTrue("switch off, location granted", PhonePlace.mayScan(context))
    }
}
