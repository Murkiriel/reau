// Two locks, not reference counted, each released before it is taken again.
package com.reau.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * The two locks of the projection, both tagged [ProjectionService.LOCK_TAG]: a partial wake lock (the CPU keeps
 * running with the screen off) and a Wi-Fi lock (Wi-Fi power saving does not drop the dash's link).
 *
 * The Wi-Fi lock asks for "high performance". On the Android 16 emulator the system files it
 * as a low-latency lock (`dumpsys wifi`: type 4), a kind Android documents as acting only with the app on screen.
 * What it does on the phone with the screen off is not verified.
 */
internal class ProjectionLocks(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        // Never two of each: whatever is held is let go first.
        release()
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ProjectionService.LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        @Suppress("DEPRECATION") // see the class comment
        val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, ProjectionService.LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private companion object {
        /** A safety net, should a wake lock ever be left behind: no ride lasts this long. */
        const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
    }
}
