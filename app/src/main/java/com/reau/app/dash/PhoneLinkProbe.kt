package com.reau.app.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Reads the phone's Wi-Fi link to the dash for [LinkReport]. Needs no new permission (ACCESS_WIFI_STATE and
 * ACCESS_NETWORK_STATE are already in the manifest); the SSID is never read.
 *
 * The dash network's own [WifiInfo] comes first: it is the network REau asked for, and the one the dash's UDP goes
 * over. When Android has no such snapshot yet, the phone's active Wi-Fi stands in, marked as not the dash network.
 */
class PhoneLinkProbe(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** One reading, or null when Android refuses it (never throws: the heartbeat that calls it must not die of a log). */
    fun read(network: Network?): LinkSample? = runCatching {
        val own = network?.let { cm.getNetworkCapabilities(it)?.transportInfo as? WifiInfo }
        @Suppress("DEPRECATION")
        val info = own ?: wifi.connectionInfo ?: return@runCatching null
        LinkSample(
            rssiDbm = info.rssi.takeIf { it > NO_RSSI },
            txMbps = info.txLinkSpeedMbps.takeIf { it > 0 },
            rxMbps = info.rxLinkSpeedMbps.takeIf { it > 0 },
            mhz = info.frequency.takeIf { it > 0 },
            powerSave = power.isPowerSaveMode,
            screenOn = power.isInteractive,
            viaDashNetwork = own != null,
        )
    }.getOrNull()

    private companion object {
        /** WifiInfo's own "no signal reading" (INVALID_RSSI is hidden from the public SDK). */
        const val NO_RSSI = -127
    }
}
