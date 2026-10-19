package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On Android's own sockets: the one receive buffer gives back a larger packet
 * after a smaller one whole. The JVM test cannot tell (JDK 21 does not cut a reused packet to its last length); the
 * DatagramSocket contract says a receive may cut the message to the packet's length, which the previous receive set.
 */
@RunWith(AndroidJUnit4::class)
class DashSocketReceiveDeviceTest {
    private var socket: DashSocket? = null
    private var dash: DatagramSocket? = null

    @After
    fun tearDown() {
        socket?.close()
        dash?.close()
    }

    private fun next(s: DashSocket): ByteArray {
        repeat(10) { s.receiveBlocking()?.let { return it } }
        throw AssertionError("nothing arrived in 5 s")
    }

    @Test
    fun aLargerPacketAfterASmallerOneComesBackWhole() {
        val s = DashSocket().also { socket = it }
        val d = DatagramSocket().also { dash = it }
        val to = InetSocketAddress("127.0.0.1", DashSocket.RX_PORT)
        val small = ByteArray(20) { 0x55 }
        val large = ByteArray(300) { (it % 251).toByte() }
        d.send(DatagramPacket(small, small.size, to))
        assertArrayEquals(small, next(s))
        d.send(DatagramPacket(large, large.size, to))
        assertArrayEquals("whole, not cut to the small one's 20 bytes", large, next(s))
    }
}
