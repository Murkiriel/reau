package com.reau.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.telephony.TelephonyManager
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.weather.InfoRing

/**
 * The phone's own state for the dash's 1 Hz heartbeat: battery, charging, GPS on and cell signal (the sticky battery
 * broadcast, `isProviderEnabled("gps")`, the cell signal level above 0), the music and the call volume (AudioManager, in the dash's 0 to 10
 * steps) and the heading of the last GPS fix. Without it the dash shows a battery of 62 % and a
 * GPS and a signal that are always on, the values of the capture the heartbeat's template came from.
 *
 * The heading is only what [GpsWatch] already hears: the GPS is listened to for the info ring's GPS card (with "Usar a localização" on), and
 * this never turns it on; with no fix, or under 1 m/s, or a fix older than 10 s, there is no heading and the field stays at zero.
 *
 * Cheap on purpose (asking for every cell's info once a second would cost battery): the battery is the
 * sticky broadcast, not a receiver; the GPS switch and the signal level are plain reads. None of them needs the location
 * permission, and a read that fails leaves its field as the template has it.
 */
internal class PhoneStatusReader(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)

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
            musicLevel = level(AudioManager.STREAM_MUSIC),
            callLevel = level(AudioManager.STREAM_VOICE_CALL),
            bearingDeg = headingOf(GpsWatch.read()),
        )
    }.getOrNull()

    /** A stream's volume as the dash's 0 to 10 level; unknown when the phone cannot say. Two plain reads, nothing allocated. */
    private fun level(stream: Int): Int = runCatching {
        audio?.let { DashCommands.volumeLevel(it.getStreamVolume(stream), it.getStreamMaxVolume(stream)) }
    }.getOrNull() ?: DashCommands.LEVEL_UNKNOWN

    companion object {
        /**
         * [level] out of [scale] is the battery (null when either is unknown); [batteryStatus] is BatteryManager's, charging
         * or full counting as charging; [gpsOn] and [signalLevel] null (unreadable) keep what the
         * template says: GPS on, signal present. [musicLevel], [callLevel] and [bearingDeg] are as [DashCommands.PhoneStatus] takes them.
         */
        @Suppress("LongParameterList") // the status's own fields, each as the system gives it
        fun statusOf(
            level: Int, scale: Int, batteryStatus: Int, gpsOn: Boolean?, signalLevel: Int?,
            musicLevel: Int = DashCommands.LEVEL_UNKNOWN, callLevel: Int = DashCommands.LEVEL_UNKNOWN, bearingDeg: Int = DashCommands.BEARING_NONE,
        ): DashCommands.PhoneStatus? {
            if (level < 0 || scale <= 0) return null
            return DashCommands.PhoneStatus(
                batteryPercent = (level * PERCENT / scale).coerceIn(0, PERCENT),
                charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
                gpsOn = gpsOn ?: true,
                signalPresent = signalLevel?.let { it > 0 } ?: true,
                musicLevel = musicLevel,
                callLevel = callLevel,
                bearingDeg = bearingDeg,
            )
        }

        /**
         * The heading for the dash's 05 2D from what the GPS says ([GpsWatch.read], null while it is not listened to or has said nothing for 10 s):
         * the last fix's heading in whole degrees, 0 to 359, only while that fix says the phone moves at 1 m/s or more; otherwise
         * [DashCommands.BEARING_NONE], and the field stays at zero.
         */
        fun headingOf(gps: com.reau.app.weather.GpsReading?): Int = gps?.let(InfoRing::gpsBearing) ?: DashCommands.BEARING_NONE

        /** [status] as it may go to the dash: the heading only with the rider's "Usar a localização" on ([useLocation]). */
        fun withLocationRule(status: DashCommands.PhoneStatus, useLocation: Boolean): DashCommands.PhoneStatus =
            if (status.bearingDeg >= 0 && !useLocation) status.copy(bearingDeg = DashCommands.BEARING_NONE) else status

        private const val PERCENT = 100
    }
}
