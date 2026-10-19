package com.reau.app.web

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may reach the web server: the local network and the phone itself, never the internet. */
class LocalNetworkTest {
    @Test
    fun `an address of the phone itself is the phone, another is not`() {
        assertTrue(LocalNetwork.isThePhone(java.net.InetAddress.getByName("127.0.0.1")))
        assertTrue(LocalNetwork.isThePhone(java.net.InetAddress.getByName("::1")))
        assertTrue("the machine's own address, as a browser on the phone reaches the server by its Wi-Fi address",
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.all { LocalNetwork.isThePhone(it) })
        assertFalse(LocalNetwork.isThePhone(java.net.InetAddress.getByName("8.8.8.8")))
        assertFalse("a neighbour on the same network", LocalNetwork.isThePhone(java.net.InetAddress.getByName("192.0.2.55")))
    }

    private fun allowed(address: String) = LocalNetwork.isAllowed(InetAddress.getByName(address))

    @Test
    fun `the private ranges of IPv4 are allowed`() {
        val private = listOf("10.1.2.3", "10.0.0.1", "10.255.255.254", "192.168.0.5", "192.168.43.1", "172.16.0.1", "172.31.255.1", "169.254.1.1", "127.0.0.1")
        for (a in private) assertEquals(a, true, allowed(a))
    }

    @Test
    fun `the edges of the 172 range are respected`() {
        assertEquals(false, allowed("172.15.255.255"))
        assertEquals(false, allowed("172.32.0.1"))
    }

    @Test
    fun `public IPv4 is refused`() {
        for (a in listOf("8.8.8.8", "1.1.1.1", "200.1.2.3", "192.169.0.1", "11.0.0.1", "100.64.0.1")) assertEquals(a, false, allowed(a))
    }

    @Test
    fun `IPv6 loopback, link-local and unique-local are allowed, global is refused`() {
        assertEquals(true, allowed("::1"))
        assertEquals(true, allowed("fe80::1"))
        assertEquals(true, allowed("fd00::1234"))
        assertEquals(true, allowed("fc00::1"))
        assertEquals(false, allowed("2001:4860:4860::8888"))
    }

    @Test
    fun `an IPv4 inside IPv6 is judged as IPv4`() {
        assertEquals(true, allowed("::ffff:192.168.0.5"))
        assertEquals(false, allowed("::ffff:8.8.8.8"))
    }

    @Test
    fun `the unspecified and the multicast addresses are refused`() {
        assertEquals(false, allowed("0.0.0.0"))
        assertEquals(false, allowed("224.0.0.1"))
        assertEquals(false, allowed("::"))
    }
}
