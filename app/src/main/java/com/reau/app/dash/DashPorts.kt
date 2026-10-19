package com.reau.app.dash

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Whether another app holds the dash's port.
 *
 * Another dash app may open UDP 2002 with address reuse on, like [DashSocket] does, so both could bind it at once
 * and the dash's packets would go to one or the other. A bind WITHOUT reuse fails while any socket holds the
 * port, whatever its options: that is the check. It holds the port only for the instant of the bind.
 */
object DashPorts {
    /** Where the dash answers the phone ([DashSocket.RX_PORT]). */
    const val DASH_RX_PORT = DashSocket.RX_PORT

    fun isFree(port: Int): Boolean = try {
        DatagramSocket(null).use {
            it.reuseAddress = false
            it.bind(InetSocketAddress(port))
        }
        true
    } catch (_: SocketException) {
        // A BindException (EADDRINUSE) is one; any other is a socket the dash session could not open either.
        false
    }
}
