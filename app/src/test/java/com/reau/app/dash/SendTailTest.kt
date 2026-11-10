package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line of what REau was sending before the dash went quiet (01/11/2026: no sign in the log of what came just before the Wi-Fi hung). */
class SendTailTest {
    private val s = 1_000_000_000L
    private val ms = 1_000_000L

    @Test
    fun `nothing sent says so`() {
        val line = SendTail().reportOnce("the Wi-Fi was lost", null, nowNs = 100 * s)
        assertEquals("send tail before the Wi-Fi was lost: no RTP sent; no keyframe, no command", line)
    }

    @Test
    fun `bytes and packets per second, the biggest burst, the last keyframe and command`() {
        val tail = SendTail()
        // Second 90: three single packets, 10 ms apart. Second 95: a keyframe of 20 packets back to back (100 us apart).
        listOf(0L, 10L, 20L).forEach { tail.rtp(1_000, 90 * s + it * ms) }
        repeat(20) { tail.rtp(1_200, 95 * s + it * 100_000L) }
        tail.keyframe(24_000, 20, 95 * s)
        tail.command("0501", 96 * s)
        val line = tail.reportOnce("the Wi-Fi was lost", "on this network for 59 s", nowNs = 100 * s)!!
        assertTrue(line, line.startsWith("send tail before the Wi-Fi was lost (on this network for 59 s): the last 10 s of RTP (up to the last packet, 4 s ago)"))
        // 23 packets and 27 kB in the 10 s up to second 95: 2.3 pkt/s, 2.7 kB/s.
        assertTrue(line, "2.3 pkt/s 2.7 kB/s" in line)
        // Oldest first: second 86 to 95; 90 has 3 packets of 3.0 kB, 95 has 20 of 24.0 kB.
        assertTrue(line, "per second oldest first 0p/0.0k 0p/0.0k 0p/0.0k 0p/0.0k 3p/3.0k 0p/0.0k 0p/0.0k 0p/0.0k 0p/0.0k 20p/24.0k," in line)
        assertTrue(line, "biggest burst 20 pkts;" in line)
        assertTrue(line, "last keyframe 24000 B in 20 pkts 5.0 s before, last command 0501 4.0 s before" in line)
    }

    @Test
    fun `packets further apart than the gap are not one burst`() {
        val tail = SendTail()
        repeat(5) { tail.rtp(100, 50 * s + it * (SendTail.BURST_GAP_NS + 1)) }
        assertTrue(tail.reportOnce("x", null, nowNs = 51 * s)!!.contains("biggest burst 1 pkts"))
    }

    @Test
    fun `the window is the seconds up to the last packet, not up to when the line is written`() {
        val tail = SendTail()
        tail.rtp(1_000, 10 * s)
        val line = tail.reportOnce("a watchdog fired", null, nowNs = 14 * s)!!
        assertTrue(line, "(up to the last packet, 4 s ago)" in line)
        assertTrue(line, "0p/0.0k 1p/1.0k," in line)
    }

    @Test
    fun `a line written many seconds late still has its window, the ring keeps more than the window`() {
        val tail = SendTail()
        (0 until 10).forEach { tail.rtp(500, (40 + it) * s) }
        val line = tail.reportOnce("x", null, nowNs = (49 + SendTail.SLOTS - SendTail.WINDOW_S) * s)!!
        assertTrue(line, "1.0 pkt/s 0.5 kB/s" in line)
    }

    @Test
    fun `seconds older than the ring are overwritten and do not leak into a later window`() {
        val tail = SendTail()
        tail.rtp(9_999, 5 * s)
        tail.rtp(100, (5 + SendTail.SLOTS) * s)
        val line = tail.reportOnce("x", null, nowNs = (5 + SendTail.SLOTS) * s)!!
        assertTrue(line, "0.1 pkt/s 0.0 kB/s" in line)
        assertTrue(line, "1p/0.1k" in line && "9999" !in line && "10.0k" !in line)
    }

    @Test
    fun `a loss and the watchdog after it are one event, said once`() {
        val tail = SendTail()
        tail.rtp(100, 10 * s)
        assertNotNull(tail.reportOnce("the Wi-Fi was lost", null, nowNs = 11 * s))
        assertNull(tail.reportOnce("a watchdog fired", null, nowNs = 15 * s))
        assertNotNull("a new loss, a minute on", tail.reportOnce("the Wi-Fi was lost", null, nowNs = 75 * s))
    }

    @Test
    fun `clear forgets everything, a new session starts over`() {
        val tail = SendTail()
        tail.rtp(100, 10 * s)
        tail.keyframe(1, 1, 10 * s)
        tail.command("0501", 10 * s)
        tail.reportOnce("x", null, nowNs = 11 * s)
        tail.clear()
        assertEquals("send tail before y: no RTP sent; no keyframe, no command", tail.reportOnce("y", null, nowNs = 12 * s))
    }

    @Test
    fun `a clock that reads negative, as nanoTime may, keeps its seconds apart`() {
        val tail = SendTail()
        tail.rtp(1_000, -5 * s)
        tail.rtp(1_000, -4 * s)
        val line = tail.reportOnce("x", null, nowNs = -3 * s)!!
        assertTrue(line, "0.2 pkt/s 0.2 kB/s" in line)
    }

    // ── The commands: the periodic heartbeats are not "the last command" ───────────────────────────

    @Test
    fun `the status heartbeat and the projection frame are not commands, a card is`() {
        val stats = DashStats()
        stats.sent(DashCommands.heartbeat())
        stats.sent(DashCommands.projectionFrame())
        assertTrue(stats.sendTail.reportOnce("x", null, nowNs = System.nanoTime())!!.endsWith("no keyframe, no command"))
        stats.sent(DashCommands.versionQuery())
        val key = DashStats.sentKey(DashCommands.versionQuery())
        assertNotNull(key)
        stats.sendTail.clear()
        stats.sent(DashCommands.versionQuery())
        assertTrue(stats.sendTail.reportOnce("x", null, nowNs = System.nanoTime())!!.contains("last command $key "))
    }

    @Test
    fun `the heartbeat and the projection frame have a first TLV the stats can name`() {
        assertNotNull(DashStats.sentKey(DashCommands.heartbeat()))
        assertEquals("0556", DashStats.sentKey(DashCommands.projectionFrame()))
    }

    @Test
    fun `the socket's RTP sends feed the tail`() {
        val stats = DashStats()
        val loopback = java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 1)
        DashSocket(null, ctrlDest = loopback, rtpDest = loopback).use { sock ->
            sock.stats = stats
            sock.sendRtp(ByteArray(1_200))
            sock.sendRtp(ByteArray(800))
        }
        val line = stats.sendTail.reportOnce("x", null, nowNs = System.nanoTime())!!
        assertTrue(line, "0.2 pkt/s 0.2 kB/s" in line)
    }
}
