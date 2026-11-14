package com.reau.app.dash

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.reau.app.dash.DashSsid.candidateMatchesActive
import com.reau.app.dash.DashSsid.matchesPendingSsid
import com.reau.app.dash.DashSsid.normalizeSsid

/**
 * What [DashWifiManager] reads from Android about a Wi-Fi the phone joined by itself (a suggestion, a saved network). It keeps
 * nothing of the request: the name asked for and the refused networks are given at each call, read by the manager at that moment.
 */
internal class JoinedWifi(
    private val cm: ConnectivityManager,
    private val wifi: WifiManager,
) {
    /** The currently-associated WiFi network, IF its SSID matches [ssid]/[prefix] —
     * lets the manager's request reuse a connection the system already made instead of opening a new,
     * possibly-redundant request. A network [isRefused] says was refused (EPERM) is never the one. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // allNetworks: the callbacks that replace it do not answer a one-off question
    fun find(ssid: String, prefix: Boolean, isRefused: (Network) -> Boolean): Pair<Network, String>? {
        val activeSsid = activeSsid()?.takeIf { matchesPendingSsid(it, ssid, prefix) } ?: return null
        val wifiNetwork = cm.allNetworks.firstOrNull { candidate ->
            if (isRefused(candidate)) return@firstOrNull false
            val caps = cm.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val candidateSsid = normalizeSsid((caps.transportInfo as? WifiInfo)?.ssid).ifBlank { null }
            candidateMatchesActive(candidateSsid, activeSsid)
        } ?: return null
        return wifiNetwork to activeSsid
    }

    @SuppressLint("MissingPermission")
    private fun activeSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        normalizeSsid(wifi.connectionInfo?.ssid).ifBlank { null }
    }.getOrNull()

    /** Android still knows [joined] as a Wi-Fi network ([JoinedNetwork.usable]); `allNetworks` can list one that is gone. */
    fun usable(joined: Network): Boolean {
        val caps = cm.getNetworkCapabilities(joined)
        return JoinedNetwork.usable(caps != null, caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }

    /** Read the connected network's SSID (strips the surrounding quotes Android adds). */
    fun ssidOf(network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val info = caps.transportInfo as? WifiInfo ?: return ""
        return normalizeSsid(info.ssid)
    }
}
