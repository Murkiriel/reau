package com.reau.app.web

import androidx.annotation.StringRes
import com.reau.app.R
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * The address the screen shows: `http://<ip>:<port>` for each address the phone has on a local network. The PIN is
 * typed on the page (and shown beside the address), not carried in the address.
 */
object WebAddress {
    /** One address for each IPv4 [addresses] that is the phone's own on a local network (not loopback, not the internet). */
    fun urls(addresses: List<InetAddress>, port: Int): List<String> =
        addresses.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && LocalNetwork.isAllowed(it) }
            .mapNotNull { it.hostAddress }
            .distinct()
            .map { "http://$it:$port" }

    @StringRes
    fun viewersText(count: Int): Int = when (count) {
        0 -> R.string.web_viewers_none
        1 -> R.string.web_viewers_one
        else -> R.string.web_viewers_many
    }

    /** Wi-Fi, the phone's own hotspot, Wi-Fi Direct, cable and USB or Bluetooth tethering: where a browser of the local network can come from. */
    private val LOCAL_LINK = Regex("^(wlan|swlan|ap|eth|rndis|usb|p2p|bt-pan|ncm)[-\\w]*$")

    /** Whether the interface called [name] is one a browser can reach the phone by (not the mobile data, a VPN or a tunnel). */
    fun isLocalLink(name: String): Boolean = LOCAL_LINK.matches(name)

    /** The addresses of this phone's local network interfaces that are up (what [urls] chooses from); empty if Android refuses. */
    fun phoneAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && isLocalLink(it.name) }
            .flatMap { it.inetAddresses.toList() }
    } catch (_: SocketException) {
        emptyList()
    }
}
