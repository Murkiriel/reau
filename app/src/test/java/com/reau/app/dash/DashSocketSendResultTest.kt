package com.reau.app.dash

import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `DashSocket.send` says whether the packet reached the wire (the exit sequence's log tells it for each command); it never throws, as before. */
class DashSocketSendResultTest {
    private var socket: DashSocket? = null

    @After
    fun tearDown() {
        socket?.close()
    }

    private val packet = ByteArray(17)

    @Test
    fun `a packet that went to the wire is reported as sent`() {
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)
        val s = DashSocket(ctrlDest = loopback, rtpDest = loopback).also { socket = it }
        assertTrue(s.send(packet))
    }

    @Test
    fun `a packet held back by a link that is down is reported as not sent`() {
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)
        val s = DashSocket(ctrlDest = loopback, rtpDest = loopback).also { socket = it }
        repeat(5) { s.testOnlySendFailed(java.io.IOException("link down")) }
        assertFalse(s.send(packet))
    }
}
