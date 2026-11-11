package com.reau.app.service

import android.net.wifi.WifiManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi lock's mode: "high performance", always. The experimental switch of the bind retries used to ask for "low latency" too; that
 * part changed nothing on Android 14 and later (the system swaps the one for the other) and is gone from it.
 */
class ProjectionLocksTest {
    @Suppress("DEPRECATION")
    private val highPerf = WifiManager.WIFI_MODE_FULL_HIGH_PERF

    @Test
    fun `the lock is high performance`() {
        assertEquals(highPerf, ProjectionLocks.WIFI_MODE)
    }

    @Test
    fun `the lock is taken the same way whatever the experimental switch says, and the log line says nothing of it`() {
        val locks = File("src/main/java/com/reau/app/service/ProjectionLocks.kt").readText()
        val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
        assertTrue(locks.contains("fun acquire() {"))
        assertTrue(locks.contains(".createWifiLock(WIFI_MODE, ProjectionService.LOCK_TAG)"))
        assertFalse("no low-latency lock any more", locks.contains("lowLatency") || locks.contains("WIFI_MODE_FULL_LOW_LATENCY"))
        assertTrue(service.contains("locks.acquire()"))
        assertTrue(service.contains("log(\"projection: foreground with wake and Wi-Fi locks (\$took)\")"))
        assertFalse("the switch does not reach the lock", service.contains("experimentWifiBind"))
    }
}
