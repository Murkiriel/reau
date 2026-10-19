package com.reau.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.telephony.TelephonyManager
import com.reau.app.dash.protocol.DashCommands

/**
 * The phone's own state for the dash's 1 Hz heartbeat: battery, charging, GPS on and cell signal (the sticky battery
 * broadcast, `isProviderEnabled("gps")`, the cell signal level above 0). Without it the dash shows a battery of 62 % and a
 * GPS and a signal that are always on, the values of the capture the heartbeat's template came from.
 *
 * Cheap on purpose (asking for every cell's info once a second would cost battery): the battery is the
 * sticky broadcast, not a receiver; the GPS switch and the signal level are plain reads. None of them needs the location
 * permission, and a read that fails leaves its field as the template has it.
 */
internal class PhoneStatusReader(context: Context) {
    private val app = context.applicationContext

    /** The status now, or null when even the battery cannot be read (the heartbeat then keeps the template's bytes). */
    fun read(): DashCommands.PhoneStatus? = runCatching {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val gps = runCatching { app.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        val signal = runCatching { app.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull()
        statusOf(
            battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            gps,
            signal,
        )
    }.getOrNull()

    companion object {
        /**
         * [level] out of [scale] is the battery (null when either is unknown); [batteryStatus] is BatteryManager's, charging
         * or full counting as charging; [gpsOn] and [signalLevel] null (unreadable) keep what the
         * template says: GPS on, signal present.
         */
        fun statusOf(level: Int, scale: Int, batteryStatus: Int, gpsOn: Boolean?, signalLevel: Int?): DashCommands.PhoneStatus? {
            if (level < 0 || scale <= 0) return null
            return DashCommands.PhoneStatus(
                batteryPercent = (level * PERCENT / scale).coerceIn(0, PERCENT),
                charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
                gpsOn = gpsOn ?: true,
                signalPresent = signalLevel?.let { it > 0 } ?: true,
            )
        }

        private const val PERCENT = 100
    }
}
