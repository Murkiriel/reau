package com.reau.app.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * What the phone can say of its own Wi-Fi and networks at the moment the dash's Wi-Fi is lost, for the ride log (plan 25). The system's own lines about
 * the loss (`wpa_supplicant`'s reason, `ConnectivityService`) need `READ_LOGS`, which an ordinary app does not have: they are collected by adb
 * (`scripts/coletar_do_celular.sh`). This is the part that needs no special permission (`ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`): read once, at the
 * loss, nothing allocated per packet, no name and no address.
 */
internal object PhoneSide {
    /** The line for [LinkLoss.phoneSideLine], read now. Never throws: what Android refuses to say is `unknown`. */
    fun now(context: Context): String {
        val wifi = context.getSystemService(WifiManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val radio = runCatching { wifi?.wifiState }.getOrNull()?.let(::radioName)
        // The main Wi-Fi connection's state; the SSID and BSSID are not asked for and not written.
        @Suppress("DEPRECATION") // the only way to ask the supplicant's state without a callback; a connection object is not needed for it
        val supplicant = runCatching { wifi?.connectionInfo?.supplicantState?.name }.getOrNull()
        @Suppress("DEPRECATION") // the networks Android has up right now: the callback API gives them only as they change
        val all = runCatching { connectivity?.allNetworks?.toList() }.getOrNull().orEmpty()
        val nets = all.mapNotNull { net -> runCatching { connectivity?.getNetworkCapabilities(net) }.getOrNull() }.map(::netOf)
        val default = runCatching { connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } }.getOrNull()?.let(::netOf)
        return LinkLoss.phoneSideLine(radio, supplicant, nets, default)
    }

    internal fun radioName(state: Int): String = when (state) {
        WifiManager.WIFI_STATE_DISABLED -> "disabled"
        WifiManager.WIFI_STATE_DISABLING -> "disabling"
        WifiManager.WIFI_STATE_ENABLED -> "enabled"
        WifiManager.WIFI_STATE_ENABLING -> "enabling"
        else -> "unknown"
    }

    private fun netOf(caps: NetworkCapabilities) = LinkLoss.PhoneNet(
        kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        },
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}
