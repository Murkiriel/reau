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
 * The Wi-Fi lock asks for "high performance". Android's documentation (WifiManager, `WIFI_MODE_FULL_HIGH_PERF`, deprecated in API 34)
 * says that from Android 14 that request is swapped by the system for a low-latency lock, with all the limits of that lock: it acts only
 * while the phone is joined to an access point, the screen is on and the app is in front. It is what `dumpsys wifi` shows (type 4).
 * So on Android 14 and later no Wi-Fi lock of the app acts with the phone in a pocket, screen off; what the lock adds there is not verified.
 *
 * Experiment ([com.reau.app.settings.REauSettings.experimentWifiBind]): on Android 10 and later the lock is asked as "low latency" itself
 * ([wifiMode]). On Android 10 to 13 that is a different lock; on Android 14 and later it is the very lock the system already gives
 * ([sameLockEitherWay]), so there the switch changes nothing about the lock. It is held for the whole projection either way.
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

        /**
         * Whether, on Android [sdk], asking for "high performance" and asking for "low latency" end in the same lock: from Android 14
         * (API 34) the system swaps the first for the second.
         */
        internal fun sameLockEitherWay(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        /**
         * What the ride log's line of the locks says of the Wi-Fi lock: nothing with the [experiment] off (the line as it always was), and
         * with it on the [mode] taken and, on Android 14 and later, that the switch did not change the lock.
         */
        internal fun logWords(experiment: Boolean, mode: Int, sdk: Int): String = when {
            !experiment -> ""
            sameLockEitherWay(sdk) && mode == WifiManager.WIFI_MODE_FULL_LOW_LATENCY ->
                ", Wi-Fi lock: ${modeName(mode)} (experiment; on Android 14 and later the system gives this same lock without the switch, " +
                    "and it acts only with the screen on and the app in front)"
            else -> ", Wi-Fi lock: ${modeName(mode)} (experiment)"
        }

        /** The ride log's words for the mode taken. */
        internal fun modeName(mode: Int): String =
            if (mode == WifiManager.WIFI_MODE_FULL_LOW_LATENCY) "low latency" else "high performance"

        /** A safety net, should a wake lock ever be left behind: no ride lasts this long. */
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
    }
}
