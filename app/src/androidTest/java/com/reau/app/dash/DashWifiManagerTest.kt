package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the framework-calling half of DashWifiManager.findAlreadyConnectedDashNetwork
 * (the pure decision inside it, candidateMatchesActive, has full unit coverage in
 * DashWifiManagerHelpersTest — this only exercises the real ConnectivityManager/WifiManager
 * enumeration around it, which needs a real Context and can't be unit-tested). Best-effort: the
 * test environment is never actually joined to a "RE_*" dash network, so this can only assert
 * "doesn't crash", same pattern as DashWifiSuggestionsTest.
 */
@RunWith(AndroidJUnit4::class)
class DashWifiManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: DashWifiManager? = null

    @After
    fun tearDown() {
        manager?.disconnect()
        scope.cancel()
    }

    private fun shell(command: String) {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(what, condition())
    }

    /**
     * A dash network the phone had joined by itself is reused without a
     * request of REau's own, and nothing told REau when it went: every try for 15 minutes bound to the
     * same dead network (EPERM). The emulator's own Wi-Fi stands for that dash network here.
     */
    @Test
    fun aDashNetworkJoinedBeforeIsWatchedAndItsLossIsNoticed() {
        // The Wi-Fi name is read with the location permission, as REau does on the phone.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        shell("cmd location set-location-enabled true")
        Thread.sleep(1_000)
        // Android hands the Wi-Fi name only to an app on screen, as REau is when the rider opens it near the bike.
        androidx.test.core.app.ActivityScenario.launch(com.reau.app.MainActivity::class.java).use {
            val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            val ssid = wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            assumeTrue("the emulator's Wi-Fi name is readable", ssid != null)
            val dash = DashWifiManager(context, scope).also { manager = it }
            dash.connect(ssid!!)
            await("joined through the network already there", 10_000) {
                dash.state.value.status == WifiConnStatus.CONNECTED && dash.network != null
            }
            try {
                shell("svc wifi disable")
                await("the loss noticed", 20_000) { dash.network == null }
            } finally {
                shell("svc wifi enable")
            }
        }
    }

    @Test
    fun connectAgainstANonExistentSsidExercisesTheAlreadyConnectedScanWithoutCrashing() {
        val wifiManager = DashWifiManager(context, scope).also { manager = it }
        // No real dash nearby in a test environment — this SSID is guaranteed not to be the one
        // the test device is actually joined to, so findAlreadyConnectedDashNetwork() falls
        // through to a real requestNetwork() call. The point of this test is that the whole
        // connect() path (including the "already connected?" scan) runs clean end to end.
        wifiManager.connect("RE_NONEXISTENT_TEST_SSID", "12345678")
    }
}
