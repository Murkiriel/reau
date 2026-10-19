package com.reau.app.web

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address the screen shows: the phone's own addresses on the local network and the port. */
class WebAddressTest {
    private fun ips(vararg text: String) = text.map { InetAddress.getByName(it) }

    @Test
    fun `an address is http, the ip and the port, with no pin in it`() {
        assertEquals(
            listOf("http://192.168.0.5:8780"),
            WebAddress.urls(ips("192.168.0.5"), 8780),
        )
    }

    @Test
    fun `only the phone's addresses on a local network are offered, in the order they came`() {
        val urls = WebAddress.urls(ips("fe80::1", "127.0.0.1", "8.8.8.8", "10.1.2.3", "192.168.43.1", "100.64.0.2", "169.254.7.7", "::1"), 8781)
        assertEquals(
            listOf("http://10.1.2.3:8781", "http://192.168.43.1:8781", "http://169.254.7.7:8781"),
            urls,
        )
    }

    @Test
    fun `an address that comes twice is offered once`() {
        assertEquals(1, WebAddress.urls(ips("192.168.0.5", "192.168.0.5"), 8780).size)
    }

    @Test
    fun `no local address is no address`() {
        assertTrue(WebAddress.urls(emptyList(), 8780).isEmpty())
        assertTrue(WebAddress.urls(ips("8.8.8.8", "::1"), 8780).isEmpty())
    }

    @Test
    fun `only the Wi-Fi, hotspot, cable and USB interfaces are offered, not the mobile data or a VPN`() {
        for (name in listOf("wlan0", "wlan1", "swlan0", "ap0", "eth0", "rndis0", "usb0", "p2p-wlan0-0", "bt-pan", "ncm0")) {
            assertTrue(name, WebAddress.isLocalLink(name))
        }
        for (name in listOf("rmnet_data0", "rmnet1", "ccmni0", "tun0", "ppp0", "v4-rmnet_data2", "dummy0", "lo", "wg0", "ipsec0", "")) {
            assertEquals(name, false, WebAddress.isLocalLink(name))
        }
    }

    @Test
    fun `the viewers line picks the text by the count`() {
        assertEquals(com.reau.app.R.string.web_viewers_none, WebAddress.viewersText(0))
        assertEquals(com.reau.app.R.string.web_viewers_one, WebAddress.viewersText(1))
        assertEquals(com.reau.app.R.string.web_viewers_many, WebAddress.viewersText(2))
    }
}
