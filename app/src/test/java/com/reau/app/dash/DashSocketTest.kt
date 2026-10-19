package com.reau.app.dash

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * DashSocket wraps real java.net.DatagramSocket with no Android framework dependency
 * (network=null skips the only Android call, ConnectivityManager.Network#bindSocket), so it CAN
 * bind real loopback sockets in a unit test. In practice, actual UDP payload delivery between two
 * sockets in this sandboxed environment isn't reliable (packets never arrive even on 127.0.0.1),
 * so this sticks to behavior that doesn't depend on a datagram actually completing a round trip:
 * timeout handling, close()/rebind lifecycle, and the "never throws" contract on send.
 */
class DashSocketTest {
    private var socket: DashSocket? = null

    @After
    fun tearDown() {
        // Ports 2000/2002 are fixed by the K1G protocol (DashSocket binds them explicitly) --
        // MUST release them after every test or the next test's bind() fails.
        socket?.close()
        socket = null
    }

    @Test
    fun `documented protocol constants match the K1G spec`() {
        assertEquals("192.168.1.1", DashSocket.DASH_IP)
        assertEquals("192.168.1.255", DashSocket.BROADCAST)
        assertEquals(2000, DashSocket.CTRL_PORT)
        assertEquals(2002, DashSocket.RX_PORT)
        assertEquals(5000, DashSocket.RTP_PORT)
    }

    // ── injectable destination ──────────────────────
    // The default constructor must keep targeting the exact real K1G addresses -- a fake-dash test
    // (or anything else) that injects a different destination is opt-in, never the default.

    @Test
    fun `with no destination given, control packets still target the real broadcast address and port`() {
        val s = DashSocket().also { socket = it }
        assertEquals(java.net.InetSocketAddress(java.net.InetAddress.getByName(DashSocket.BROADCAST), DashSocket.CTRL_PORT), s.testOnlyCtrlDest())
    }

    @Test
    fun `with no destination given, RTP still targets the real dash address and port`() {
        val s = DashSocket().also { socket = it }
        assertEquals(java.net.InetSocketAddress(java.net.InetAddress.getByName(DashSocket.DASH_IP), DashSocket.RTP_PORT), s.testOnlyRtpDest())
    }

    @Test
    fun `an injected destination overrides both independently`() {
        val ctrl = java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 20001)
        val rtp = java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 20002)
        val s = DashSocket(ctrlDest = ctrl, rtpDest = rtp).also { socket = it }
        assertEquals(ctrl, s.testOnlyCtrlDest())
        assertEquals(rtp, s.testOnlyRtpDest())
    }

    @Test
    fun `receive returns null on timeout when nothing arrives`() = runBlocking {
        val s = DashSocket().also { socket = it }
        assertNull(s.receive())
    }

    @Test
    fun `send and sendRtp never throw even when the K1G broadcast subnet is unreachable`() {
        val s = DashSocket().also { socket = it }
        // Fire-and-forget by design (see DashSocket.send's kdoc) -- a dropped/unreachable link
        // must never surface as an exception to the caller.
        s.send(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        s.sendRtp(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `close is safe to call twice`() {
        val s = DashSocket().also { socket = it }
        s.close()
        s.close() // must not throw
    }

    @Test
    fun `close releases the bound ports so a new DashSocket can bind them again`() {
        val first = DashSocket()
        first.close()

        // If close() actually released ports 2000/2002, opening a second instance must succeed.
        val second = DashSocket().also { socket = it }
        second.close()
        socket = null
    }

    @Test
    fun `receive after close fails instead of hanging or returning stale data`() {
        val s = DashSocket()
        s.close()
        assertThrows(Exception::class.java) { runBlocking { s.receive() } }
    }

    @Test
    fun `send suppresses itself after a streak of failures, sendRtp independently`() {
        val s = DashSocket()
        s.close() // every underlying socket.send() now throws, deterministically
        org.junit.Assert.assertFalse(s.testOnlyTxSuppressed())
        org.junit.Assert.assertFalse(s.testOnlyRtpSuppressed())

        // 4 failures -- still under the streak threshold (5).
        repeat(4) { s.send(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) }
        org.junit.Assert.assertFalse(s.testOnlyTxSuppressed())

        // 5th failure crosses the threshold.
        s.send(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        org.junit.Assert.assertTrue(s.testOnlyTxSuppressed())

        // RTP has its own independent streak/suppression -- unaffected by TX's.
        org.junit.Assert.assertFalse(s.testOnlyRtpSuppressed())
        repeat(5) { s.sendRtp(byteArrayOf(1, 2, 3)) }
        org.junit.Assert.assertTrue(s.testOnlyRtpSuppressed())
    }

    @Test
    fun `suppressed send still never throws, including on its periodic probe attempts`() {
        val s = DashSocket()
        s.close()
        // Cross the threshold, then keep calling well past one probe interval (20) -- every call
        // (suppressed or the real probe attempt) must be silent, per DashSocket's fire-and-forget contract.
        repeat(45) { s.send(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) }
        org.junit.Assert.assertTrue(s.testOnlyTxSuppressed())
    }
}
