// Two locks, not reference counted, each released before it is taken again.
package com.reau.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * The two locks of the projection, both tagged [ProjectionService.LOCK_TAG]: a partial wake lock (the CPU keeps
 * running with the screen off) and a Wi-Fi lock (Wi-Fi power saving does not drop the dash's link).
 *
 * The Wi-Fi lock asks for "high performance". Android's documentation (WifiManager, `WIFI_MODE_FULL_HIGH_PERF`, deprecated in API 34)
 * says that from Android 14 that request is swapped by the system for a low-latency lock, with all the limits of that lock: it acts only
 * while the phone is joined to an access point, the screen is on and the app is in front. It is what `dumpsys wifi` shows (type 4).
 * So on Android 14 and later no Wi-Fi lock of the app acts with the phone in a pocket, screen off; what the lock adds there is not verified.
 *
 * The mode is that one with or without the experimental switch of the bind retries: an earlier version of the switch also asked for "low latency"
 * itself, which on Android 14 and later is the lock the system already gives, and on Android 10 to 13 is a lock that acts only with the screen on
 * and the app in front. That part is gone from the switch ([com.reau.app.settings.REauSettings.experimentWifiBind] keeps the bind retries).
 */
internal class ProjectionLocks(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Takes both locks. */
    fun acquire() {
        // Never two of each: whatever is held is let go first.
        release()
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ProjectionService.LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WIFI_MODE, ProjectionService.LOCK_TAG)
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

    companion object {
        /** The Wi-Fi lock's mode: "high performance", always (see the class comment). */
        @Suppress("DEPRECATION") // see the class comment
        internal const val WIFI_MODE = WifiManager.WIFI_MODE_FULL_HIGH_PERF

        /** A safety net, should a wake lock ever be left behind: no ride lasts this long. */
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
    }
}
