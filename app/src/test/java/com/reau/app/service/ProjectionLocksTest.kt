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
    fun `the two modes are told apart in the log`() {
        assertEquals("low latency", ProjectionLocks.modeName(lowLatency))
        assertEquals("high performance", ProjectionLocks.modeName(highPerf))
    }
}
