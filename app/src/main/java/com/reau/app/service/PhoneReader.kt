package com.reau.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import com.reau.app.weather.BatteryDetail
import com.reau.app.weather.LinkKind
import com.reau.app.weather.LinkReading

/**
 * What the phone says of itself for the dash's info ring: its battery (the sticky broadcast and the system's battery saver) and the link
 * it carries data on (airplane mode, the Wi-Fi or the mobile network with its generation, the satellite). Plain reads of what Android hands
 * any app: no new permission; what a permission would be needed for (the mobile network's generation, the Wi-Fi's name) is read only when
 * the app already has it, and left out otherwise. Nothing is kept or sent anywhere, and the Wi-Fi's name is never shown: only that it is
 * a Starlink's.
 */
internal object PhoneReader {
    /** The battery now, or null when Android does not say its level. */
    fun battery(context: Context): BatteryDetail? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val percent = percentOf(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        BatteryDetail(percent, charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING, saver = saver)
    }.getOrNull()

    /** [level] out of [scale] as 0 to 100; null when either is unknown. */
    fun percentOf(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else (level * PERCENT / scale).coerceIn(0, PERCENT)

    /** The link the phone carries its data on now. */
    @SuppressLint("InlinedApi") // the satellite transport is asked only from Android 15
    @Suppress("DEPRECATION") // allNetworks: the callbacks that replace it do not answer a one-off question
    fun link(context: Context): LinkReading = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        // The network that really has the internet, never a VPN laid over it and never the dash's Wi-Fi (which has none).
        val carrying = manager?.let { m ->
            (listOfNotNull(m.activeNetwork) + m.allNetworks.toList()).distinct().mapNotNull { n -> m.getNetworkCapabilities(n) }.firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }
        when {
            carrying == null -> LinkReading(if (airplane) LinkKind.AIRPLANE else LinkKind.OFFLINE)
            Build.VERSION.SDK_INT >= SATELLITE_SDK && carrying.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> LinkReading(LinkKind.SATELLITE)
            carrying.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi(context)
            carrying.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular(context)
            else -> LinkReading(LinkKind.OFFLINE)
        }
    }.getOrDefault(LinkReading(LinkKind.OFFLINE))

    @Suppress("DEPRECATION") // connectionInfo: its replacement (a network callback) does not answer a one-off question
    private fun wifi(context: Context): LinkReading {
        val info = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        val name = if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) info?.ssid else null
        val kind = if (isStarlink(name)) LinkKind.STARLINK else LinkKind.WIFI
        // A signal that cannot be read (no location permission) is shown full: better than a Wi-Fi that looks dead.
        return LinkReading(kind, bars = info?.rssi?.takeIf { it > UNKNOWN_RSSI }?.let(::wifiBars) ?: MAX_BARS)
    }

    /** A Starlink's own Wi-Fi is called "STARLINK" (or has it in its name): the only name this ever says. */
    fun isStarlink(ssid: String?): Boolean = ssid?.contains("starlink", ignoreCase = true) == true

    /** The Wi-Fi's signal in bars, 0 to 4, from its dBm. */
    fun wifiBars(rssiDbm: Int): Int = when {
        rssiDbm >= WIFI_FOUR -> 4
        rssiDbm >= WIFI_THREE -> 3
        rssiDbm >= WIFI_TWO -> 2
        rssiDbm >= WIFI_ONE -> 1
        else -> 0
    }

    @SuppressLint("MissingPermission") // the data network type is read only after the check just below
    private fun cellular(context: Context): LinkReading {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val bars = runCatching { telephony?.signalStrength?.level }.getOrNull() ?: 0
        val generation = if (granted(context, Manifest.permission.READ_PHONE_STATE)) {
            runCatching { generationOf(telephony?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN) }.getOrNull()
        } else {
            null
        }
        val operator = runCatching { telephony?.networkOperatorName }.getOrNull()
        return LinkReading(LinkKind.CELLULAR, bars.coerceIn(0, MAX_BARS), generation, operator)
    }

    /** The generation of the mobile network by Android's data network type: "5G", "4G", "3G" or "2G"; null for what is none of them. */
    @Suppress("DEPRECATION") // the CDMA and EVDO types are deprecated and still reported by old networks
    fun generationOf(networkType: Int): String? = when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_GSM,
        -> "2G"
        else -> null
    }

    private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private const val PERCENT = 100
    private const val MAX_BARS = 4
    /** What Android says for a signal it will not tell: -127. */
    private const val UNKNOWN_RSSI = -127
    private const val WIFI_FOUR = -55
    private const val WIFI_THREE = -65
    private const val WIFI_TWO = -75
    private const val WIFI_ONE = -90

    /** Android 15 is the first with the satellite transport. */
    private const val SATELLITE_SDK = 35
}
