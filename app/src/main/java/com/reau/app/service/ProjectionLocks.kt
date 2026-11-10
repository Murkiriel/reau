// Two locks, not reference counted, each released before it is taken again.
package com.reau.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * The two locks of the projection, both tagged [ProjectionService.LOCK_TAG]: a partial wake lock (the CPU keeps
 * running with the screen off) and a Wi-Fi lock (Wi-Fi power saving does not drop the dash's link).
 *
 * The Wi-Fi lock asks for "high performance". On the Android 16 emulator the system files it
 * as a low-latency lock (`dumpsys wifi`: type 4), a kind Android documents as acting only with the app on screen.
 * What it does on the phone with the screen off is not verified.
 *
 * Experiment ([com.reau.app.settings.REauSettings.experimentWifiBind]): on Android 10 and later the lock is taken as "low latency" instead
 * ([wifiMode]); Android documents "high performance" as having no effect from Android 14, and it is held for the whole projection either way.
 */
internal class ProjectionLocks(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Takes both locks and says the Wi-Fi lock's mode ([wifiMode]). */
    fun acquire(lowLatency: Boolean = false): Int {
        // Never two of each: whatever is held is let go first.
        release()
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ProjectionService.LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        val mode = wifiMode(lowLatency, Build.VERSION.SDK_INT)
        wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, ProjectionService.LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        return mode
    }

    fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    companion object {
        /**
         * The Wi-Fi lock's mode: with [lowLatency] (the experimental switch) and Android 10 or later ([sdk] 29), `WIFI_MODE_FULL_LOW_LATENCY`;
         * otherwise, as always, `WIFI_MODE_FULL_HIGH_PERF`.
         */
        @Suppress("DEPRECATION") // see the class comment
        internal fun wifiMode(lowLatency: Boolean, sdk: Int): Int =
            if (lowLatency && sdk >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF

        /** The ride log's words for the mode taken. */
        internal fun modeName(mode: Int): String =
            if (mode == WifiManager.WIFI_MODE_FULL_LOW_LATENCY) "low latency" else "high performance"

        /** A safety net, should a wake lock ever be left behind: no ride lasts this long. */
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
    }
}
