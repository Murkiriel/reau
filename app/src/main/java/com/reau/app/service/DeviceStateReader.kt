package com.reau.app.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import androidx.core.content.ContextCompat
import com.reau.app.data.DeviceState

/**
 * Reads the phone's [DeviceState] with no new permission: the battery from its sticky
 * broadcast, PowerManager, the memory, the bike's Wi-Fi ([bikeNetwork], held by REau) and the default network.
 * Whatever cannot be read stays null.
 */
class DeviceStateReader(private val context: Context, private val bikeNetwork: () -> Network?) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val display = context.getSystemService(DisplayManager::class.java)

    /** The state now; what it says of the charger, the screen and doze is also kept for the line on a link's end. */
    fun read(): DeviceState = readNow().also { com.reau.app.data.PhoneNow.update(it.charging, it.screenOn, it.dozing) }

    private fun readNow(): DeviceState {
        val battery = runCatching {
            ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrNull()
        val memory = runCatching { ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) } }.getOrNull()
        val runtime = Runtime.getRuntime()
        val defaultCaps = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
        return DeviceState(
            batteryPct = battery?.let { percent(it) },
            charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 },
            batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / TENTHS },
            batteryMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            thermal = runCatching { power.currentThermalStatus }.getOrNull(),
            headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_S) }.getOrNull()
            } else {
                null
            },
            appUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / MB).toInt(),
            appMaxMb = (runtime.maxMemory() / MB).toInt(),
            phoneAvailMb = memory?.let { (it.availMem / MB).toInt() },
            lowMemory = memory?.lowMemory,
            screenOn = runCatching { display.getDisplay(Display.DEFAULT_DISPLAY).state == Display.STATE_ON }.getOrNull(),
            interactive = runCatching { power.isInteractive }.getOrNull(),
            dozing = runCatching { power.isDeviceIdleMode }.getOrNull(),
            powerSave = runCatching { power.isPowerSaveMode }.getOrNull(),
            batteryExempt = runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            bikeWifi = bikeWifi(context, bikeNetwork()),
            data = when {
                defaultCaps == null -> DeviceState.Data.NONE
                defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DeviceState.Data.WIFI
                defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DeviceState.Data.CELL
                else -> DeviceState.Data.OTHER
            },
            dataValidated = defaultCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }


    private fun percent(battery: Intent): Int? {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * PERCENT / scale else null
    }

    companion object {
        /** The bike's Wi-Fi as Android reads it on [network] (held by REau); null when it is not there or not readable. */
        fun bikeWifi(context: Context, network: Network?): DeviceState.BikeWifi? = runCatching {
            network ?: return null
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val info = connectivity.getNetworkCapabilities(network)?.transportInfo as? WifiInfo ?: return null
            DeviceState.BikeWifi(dbm = info.rssi, mhz = info.frequency, mbps = info.linkSpeed)
        }.getOrNull()

        private const val TENTHS = 10f
        private const val PERCENT = 100
        private const val MB = 1024L * 1024
        private const val HEADROOM_FORECAST_S = 10
    }
}
