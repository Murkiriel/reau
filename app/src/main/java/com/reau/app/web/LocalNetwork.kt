package com.reau.app.web

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Who may reach the web server: the phone itself and the local network (10.x, 172.16-31.x, 192.168.x,
 * link-local, and IPv6's unique-local). Addresses of the internet, multicast and the unspecified one are refused before
 * a byte is read.
 */
object LocalNetwork {
    /**
     * Whether [address] is the phone's own: loopback, or an address of one of its network interfaces (a browser on the phone reaches the
     * server by its Wi-Fi address too). Whatever that browser plays, Android Auto takes it as the phone's media and sends it back to it,
     * and the sound goes round and round, so it is never sent the sound.
     */
    fun isThePhone(address: InetAddress): Boolean =
        address.isLoopbackAddress || runCatching { java.net.NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)

    fun isAllowed(address: InetAddress): Boolean = when {
        address.isAnyLocalAddress || address.isMulticastAddress -> false
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress -> true
        else -> address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }
}
