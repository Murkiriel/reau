package com.reau.app.dash

import com.reau.app.data.RideDiagnostics
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A packet that reaches the dash socket from an address other than 192.168.1.1 (here, loopback) is still taken, whole, as before, and the ride
 * log tells it once, with the count at the close. Nothing is rejected: the owner decided not to filter by address.
 */
class DashSocketStrayTest {
    @get:Rule val tmp = TemporaryFolder()
    private var socket: DashSocket? = null
    private var other: DatagramSocket? = null

    @After
    fun tearDown() {
        socket?.close()
        other?.close()
        RideDiagnostics.testOnlyReset()
    }

    private fun strayLines(file: File) = file.readLines().filter { "rx from " in it }

    @Test
    fun `packets from another address are taken as ever, the first is told once and the close says how many`() {
        val file = tmp.newFile("ride-stray.log")
        RideDiagnostics.testOnlySetFile(file)
        val s = DashSocket().also { socket = it }
        val sender = DatagramSocket().also { other = it }
        val packets = listOf(ByteArray(12) { 1 }, ByteArray(30) { 2 }, ByteArray(5) { 3 })
        for (bytes in packets) {
            sender.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", DashSocket.RX_PORT)))
            var got: ByteArray? = null
            repeat(10) { if (got == null) got = s.receiveBlocking() }
            assertArrayEquals("the packet is delivered whole, not rejected", bytes, got)
        }
        RideDiagnostics.logAndWait("ui", "mid")
        val told = strayLines(file)
        assertEquals("told once: $told", 1, told.size)
        assertTrue(told.single(), "127.0.0.1" in told.single() && DashSocket.DASH_IP in told.single())

        s.close()
        RideDiagnostics.logAndWait("ui", "end")
        val all = strayLines(file)
        assertEquals("the close adds the count: $all", 2, all.size)
        assertTrue(all.last(), "3 packets from 1 address(es), first 127.0.0.1" in all.last())
    }

    @Test
    fun `a packet from the dash's own address says nothing`() {
        val file = tmp.newFile("ride-dash.log")
        RideDiagnostics.testOnlySetFile(file)
        val loopback = InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), DashSocket.RTP_PORT)
        // The dash "is" loopback here: where RTP goes is the address that is expected.
        val s = DashSocket(ctrlDest = loopback, rtpDest = loopback).also { socket = it }
        val sender = DatagramSocket().also { other = it }
        val bytes = ByteArray(9) { 7 }
        sender.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", DashSocket.RX_PORT)))
        var got: ByteArray? = null
        repeat(10) { if (got == null) got = s.receiveBlocking() }
        assertArrayEquals(bytes, got)
        s.close()
        RideDiagnostics.logAndWait("ui", "end")
        assertEquals(strayLines(file).toString(), 0, strayLines(file).size)
    }
}
