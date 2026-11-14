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
    fun `a suppressed channel really sends one call in twenty, to see whether the link is back`() {
        val tx = DashSocket().apply { close() }
        repeat(5) { tx.send(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) } // the fifth failure suppresses it
        repeat(40) { tx.send(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) }
        assertEquals("40 calls, the 20th and the 40th went out: 38 held back", 38, tx.testOnlyTxSuppressedTotal())

        val rtp = DashSocket().apply { close() }
        repeat(5) { rtp.sendRtp(byteArrayOf(1, 2, 3)) }
        repeat(40) { rtp.sendRtp(byteArrayOf(1, 2, 3)) }
        assertEquals(38, rtp.testOnlyRtpSuppressedTotal())
    }

    @Test
    fun `a suppressed RTP channel whose probe goes out is no longer suppressed`() {
        val loopback = java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 45_123)
        val s = DashSocket(rtpDest = loopback)
        try {
            s.testOnlySuppressRtp()
            repeat(19) { s.sendRtp(byteArrayOf(1, 2, 3)) }
            org.junit.Assert.assertTrue("held back until the probe", s.testOnlyRtpSuppressed())
            s.sendRtp(byteArrayOf(1, 2, 3)) // the 20th is the probe, and it goes out
            assertEquals("the link is back: no more holding back", false, s.testOnlyRtpSuppressed())
            assertEquals(0, s.testOnlyRtpSuppressedTotal())
        } finally {
            s.close()
        }
    }

    @Test
    fun `sends from several threads keep the suppression counters whole`() {
        val s = DashSocket().apply { close() }
        val packet = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val threads = List(4) { Thread { repeat(5_000) { s.send(packet) } }.apply { start() } }
        threads.forEach { it.join() }
        org.junit.Assert.assertTrue(s.testOnlyTxSuppressed())
        // every call of a suppressed link is counted once, and all of them are held back but the probe, the 20th, 40th...
        val calls = s.testOnlyTxProbeCounter()
        assertEquals("held back = calls - probes: $calls calls", calls - calls / 20, s.testOnlyTxSuppressedTotal())
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

    @Test
    fun `a batch counts its packets in the same streak as single sends, and never throws`() {
        val s = DashSocket()
        s.close()
        val packet = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        s.sendBatch(listOf(packet, packet))
        s.sendBatch(listOf(packet, packet))
        org.junit.Assert.assertFalse("four failures are still under the threshold of five", s.testOnlyTxSuppressed())
        s.sendBatch(listOf(packet))
        org.junit.Assert.assertTrue("the fifth, in a batch of one, crosses it", s.testOnlyTxSuppressed())
    }

    @Test
    fun `an empty batch sends nothing and fails nothing`() {
        val s = DashSocket().also { socket = it }
        s.sendBatch(emptyList())
        org.junit.Assert.assertFalse(s.testOnlyTxSuppressed())
    }

    @Test
    fun `sends refused for being on the main thread are not a dead link and never suppress the channel`() {
        val s = DashSocket().also { socket = it }
        repeat(50) { s.testOnlySendFailed(NetworkOnMainThreadException()) }
        org.junit.Assert.assertFalse("50 main-thread sends must not silence the control channel", s.testOnlyTxSuppressed())
        assertEquals("and they do not count in the streak", 0, s.testOnlyTxFailStreak())
        assertEquals(50, s.testOnlyMainThreadWarnings())
        // Mixed in with real failures, they neither add to nor reset the streak.
        repeat(4) { s.testOnlySendFailed(java.net.SocketException("Network is unreachable")) }
        repeat(10) { s.testOnlySendFailed(NetworkOnMainThreadException()) }
        org.junit.Assert.assertFalse(s.testOnlyTxSuppressed())
        assertEquals(4, s.testOnlyTxFailStreak())
        s.testOnlySendFailed(java.net.SocketException("Network is unreachable"))
        org.junit.Assert.assertTrue("the fifth real failure still suppresses", s.testOnlyTxSuppressed())
    }

    @Test
    fun `real socket failures still turn the suppression on after five`() {
        val s = DashSocket().also { socket = it }
        repeat(4) { s.testOnlySendFailed(java.net.SocketException("sendto failed: ENETUNREACH")) }
        org.junit.Assert.assertFalse(s.testOnlyTxSuppressed())
        s.testOnlySendFailed(java.io.IOException("sendto failed: EBADF"))
        org.junit.Assert.assertTrue(s.testOnlyTxSuppressed())
        assertEquals(0, s.testOnlyMainThreadWarnings())
    }

    @Test
    fun `a send on the main thread is accused in the log as a programming bug, a few lines only`() {
        val logFile = java.nio.file.Files.createTempFile("dash-socket-main-thread", ".log").toFile()
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        try {
            val s = DashSocket().also { socket = it }
            repeat(200) { s.testOnlySendFailed(NetworkOnMainThreadException()) }
            s.testOnlySendFailed(java.net.SocketException("sendto failed: ENETUNREACH")) // a real one is still told, once
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && logFile.readText().let { "TX send failing" !in it }) Thread.sleep(20)
            val lines = logFile.readLines()
            val accused = lines.filter { "TX send refused: NetworkOnMainThreadException" in it }
            org.junit.Assert.assertTrue("the accusation says it is the caller's bug: $accused", accused.isNotEmpty() && accused.all { "programming bug" in it })
            org.junit.Assert.assertTrue("200 bad sends must not flood the log: ${accused.size} lines", accused.size in 1..3)
            org.junit.Assert.assertTrue("a main-thread send is never told as a dead link", lines.none { "link down?" in it && "NetworkOnMainThread" in it })
            org.junit.Assert.assertEquals("the real failure is told as before", 1, lines.count { "TX send failing (link down?)" in it })
        } finally {
            com.reau.app.data.RideDiagnostics.testOnlyReset()
            logFile.delete()
        }
    }

    @Test
    fun `only the exception named NetworkOnMainThreadException is taken for a main-thread send`() {
        org.junit.Assert.assertTrue(DashSocket.isMainThreadViolation(NetworkOnMainThreadException()))
        org.junit.Assert.assertFalse(DashSocket.isMainThreadViolation(java.net.SocketException("x")))
        org.junit.Assert.assertFalse(DashSocket.isMainThreadViolation(IllegalStateException("main thread")))
    }

    /** Stands for android.os.NetworkOnMainThreadException (not usable on the JVM): the guard goes by the class's simple name. */
    private class NetworkOnMainThreadException : java.io.IOException("stand-in")
}
