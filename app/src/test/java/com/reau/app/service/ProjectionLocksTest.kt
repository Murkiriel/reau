package com.reau.app.service

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Wi-Fi lock's mode: unchanged (high performance) unless the experimental switch is on and the phone is Android 10 or later. */
class ProjectionLocksTest {
    @Suppress("DEPRECATION")
    private val highPerf = WifiManager.WIFI_MODE_FULL_HIGH_PERF
    private val lowLatency = WifiManager.WIFI_MODE_FULL_LOW_LATENCY

    @Test
    fun `with the experiment off the lock is high performance on every Android`() {
        for (sdk in listOf(26, 28, 29, 33, 34, 36)) assertEquals("sdk $sdk", highPerf, ProjectionLocks.wifiMode(lowLatency = false, sdk = sdk))
    }

    @Test
    fun `with the experiment on it is low latency from Android 10 and high performance before it`() {
        assertEquals(highPerf, ProjectionLocks.wifiMode(lowLatency = true, sdk = 28))
        assertEquals(lowLatency, ProjectionLocks.wifiMode(lowLatency = true, sdk = 29))
        assertEquals(lowLatency, ProjectionLocks.wifiMode(lowLatency = true, sdk = 34))
    }

    @Test
    fun `from Android 14 asking for either mode ends in the same lock, and before it it does not`() {
        for (sdk in listOf(26, 29, 33)) assertEquals("sdk $sdk", false, ProjectionLocks.sameLockEitherWay(sdk))
        for (sdk in listOf(34, 35, 36)) assertEquals("sdk $sdk", true, ProjectionLocks.sameLockEitherWay(sdk))
    }

    @Test
    fun `with the experiment off the line of the locks says nothing of the Wi-Fi lock, on any Android`() {
        for (sdk in listOf(28, 29, 33, 34, 36)) assertEquals("sdk $sdk", "", ProjectionLocks.logWords(experiment = false, mode = highPerf, sdk = sdk))
    }

    @Test
    fun `with the experiment on Android 10 to 13 the line says the mode and nothing else`() {
        assertEquals(", Wi-Fi lock: low latency (experiment)", ProjectionLocks.logWords(experiment = true, mode = lowLatency, sdk = 33))
        assertEquals(", Wi-Fi lock: high performance (experiment)", ProjectionLocks.logWords(experiment = true, mode = highPerf, sdk = 28))
    }

    @Test
    fun `with the experiment on Android 14 the line says the switch did not change the lock and when the lock acts`() {
        val line = ProjectionLocks.logWords(experiment = true, mode = lowLatency, sdk = 34)
        assertEquals(true, line.startsWith(", Wi-Fi lock: low latency (experiment; on Android 14 and later the system gives this same lock without the switch"))
        assertEquals(true, line.contains("only with the screen on and the app in front"))
    }

    @Test
    fun `the two modes are told apart in the log`() {
        assertEquals("low latency", ProjectionLocks.modeName(lowLatency))
        assertEquals("high performance", ProjectionLocks.modeName(highPerf))
    }
}
