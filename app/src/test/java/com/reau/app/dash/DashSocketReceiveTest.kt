package com.reau.app.dash

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The receive loop reuses one 64 KB buffer per socket instead of allocating one per call, and what it hands back is
 * still each packet alone, whole and its own.
 * Packets go to the real receive port on loopback, from a plain socket standing in for the dash.
 */
class DashSocketReceiveTest {
    private var socket: DashSocket? = null
    private var dash: DatagramSocket? = null

    @After
    fun tearDown() {
        socket?.close()
        dash?.close()
    }

    private fun open(): DashSocket = DashSocket().also {
        socket = it
        dash = DatagramSocket()
    }

    private fun fromDash(bytes: ByteArray) =
        dash!!.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", DashSocket.RX_PORT)))

    private fun next(s: DashSocket): ByteArray {
        repeat(10) { s.receiveBlocking()?.let { return it } }
        throw AssertionError("nothing arrived in 5 s")
    }

    @Test
    fun `packets of different sizes one after the other come back each whole and alone`() {
        val s = open()
        val small = ByteArray(20) { 0x55 }
        val large = ByteArray(300) { (it % 251).toByte() }
        val smallAgain = ByteArray(7) { 0x11 }
        fromDash(small)
        val first = next(s)
        fromDash(large)
        val second = next(s)
        fromDash(smallAgain)
        val third = next(s)
        assertArrayEquals("the small one alone", small, first)
        assertArrayEquals("the large one whole, not cut to the small one's size", large, second)
        assertArrayEquals("the last one without the large one's tail", smallAgain, third)
        assertArrayEquals("the first one's bytes are its own, not the shared buffer", ByteArray(20) { 0x55 }, first)
    }

    @Test
    fun `one buffer serves every receive`() {
        val s = open()
        fromDash(ByteArray(10) { 1 })
        next(s)
        val buffer = s.testOnlyRxBuffer()
        fromDash(ByteArray(40) { 2 })
        next(s)
        assertSame("the same buffer, not a new 64 KB one per packet", buffer, s.testOnlyRxBuffer())
    }
}
