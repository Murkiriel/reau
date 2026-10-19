package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What REau and the dash exchanged, one `dash-stats` line per
 * 5 s window with the session's totals behind it, and a line the first time each button and telemetry kind shows up.
 */
class DashStatsTest {
    private val firsts = mutableListOf<String>()
    private val stats = DashStats(onFirst = { firsts += it })

    private fun pkt(vararg tlvs: com.reau.app.dash.protocol.Tlv) = K1GPacket.build(*tlvs)

    /** A received packet as the session reads it, once. */
    private fun rx(vararg tlvs: com.reau.app.dash.protocol.Tlv) = K1GPacket.parseIncoming(pkt(*tlvs))

    @Test
    fun `the window line counts sent packets by their first TLV and received TLVs by kind`() {
        stats.sent(pkt(K1GPacket.tlv(0x05, 0x56, 0x55)))
        stats.sent(pkt(K1GPacket.tlv(0x05, 0x56, 0x55)))
        stats.sent(pkt(K1GPacket.tlv(0x08, 0x04), K1GPacket.tlv(0x01, 0x02)))
        stats.sendFailed()
        stats.heartbeat()
        stats.received(rx(K1GPacket.tlv(0x09, 0x06, 0x55)), nowMs = 1_000)
        stats.received(rx(K1GPacket.tlv(0x09, 0x04, 0x55)), nowMs = 1_100)
        stats.received(rx(K1GPacket.tlv(0x09, 0x00, 0x00, 0x09)), nowMs = 1_200)
        stats.received(rx(K1GPacket.tlv(0x0C, 0x02, 0x01, 0x02)), nowMs = 1_300)
        assertEquals(
            "dash-stats 5.0s: tx=3 (0556=2 0804=1) rx=4 (0900=1 0904=1 0906=1 0C*=1) acks=2 buttons=1 telemetry=1 " +
                "heartbeats=1 tx-fail=1 last-rx=700ms ago | total tx=3 rx=4 acks=2 buttons=1 telemetry=1 tx-fail=1",
            stats.windowLine(windowMs = 5_000, nowMs = 2_000),
        )
    }

    /** 25 telemetry kinds a window, one each, were a fifth of the line. */
    @Test
    fun `the telemetry kinds of a family are one count in the line, the other kinds stay apart`() {
        for (kind in listOf(0x01, 0x02, 0x03, 0x26)) stats.received(rx(K1GPacket.tlv(0x0C, kind, 0x01)), nowMs = 1_000)
        stats.received(rx(K1GPacket.tlv(0x0C, 0x02, 0x01)), nowMs = 1_000) // a kind again counts again
        for (kind in listOf(0x01, 0x07)) stats.received(rx(K1GPacket.tlv(0x0F, kind, 0x01)), nowMs = 1_000)
        stats.received(rx(K1GPacket.tlv(0x01, 0x01, 0x4B)), nowMs = 1_000)
        stats.received(rx(K1GPacket.tlv(0x09, 0x06, 0x55)), nowMs = 1_000)
        val line = stats.windowLine(windowMs = 5_000, nowMs = 2_000)
        assertTrue(line, "rx=9 (0101=1 0906=1 0C*=5 0F*=2) acks=1 buttons=0 telemetry=7 " in line)
        assertFalse("no single kind is told in the window line: $line", "0C02" in line || "0F07" in line)
    }

    @Test
    fun `each telemetry kind is still told once in its own line, grouped or not`() {
        val told = mutableListOf<String>()
        val s = DashStats { told += it }
        for (kind in listOf(0x01, 0x02)) s.received(rx(K1GPacket.tlv(0x0C, kind, 0x01)), nowMs = 1_000)
        s.received(rx(K1GPacket.tlv(0x0C, 0x01, 0x01)), nowMs = 1_100)
        assertEquals(listOf("dash-stats first telemetry 0C01", "dash-stats first telemetry 0C02"), told)
    }

    @Test
    fun `a new window starts empty while the totals go on`() {
        stats.received(rx(K1GPacket.tlv(0x09, 0x06, 0x55)), nowMs = 1_000)
        stats.windowLine(windowMs = 5_000, nowMs = 2_000)
        stats.sent(pkt(K1GPacket.tlv(0x06, 0x11, 0x55)))
        assertEquals(
            "dash-stats 2.5s: tx=1 (0611=1) rx=0 () acks=0 buttons=0 telemetry=0 heartbeats=0 tx-fail=0 " +
                "last-rx=2000ms ago | total tx=1 rx=1 acks=1 buttons=0 telemetry=0 tx-fail=0",
            stats.windowLine(windowMs = 2_500, nowMs = 3_000),
        )
    }

    @Test
    fun `nothing received yet says so`() {
        val line = stats.windowLine(windowMs = 5_000, nowMs = 2_000)
        assertEquals(true, line.contains(" last-rx=never "))
    }

    @Test
    fun `the first of each button code and telemetry kind gets its own line, once`() {
        repeat(2) { stats.received(rx(K1GPacket.tlv(0x09, 0x00, 0x00, 0x09)), nowMs = 1_000) }
        stats.received(rx(K1GPacket.tlv(0x09, 0x00, 0x00, 0x0A)), nowMs = 1_000)
        repeat(3) { stats.received(rx(K1GPacket.tlv(0x0F, 0x01, 0x11)), nowMs = 1_000) }
        stats.received(rx(K1GPacket.tlv(0x0C, 0x02, 0x01)), nowMs = 1_000)
        assertEquals(
            listOf("dash-stats first button 0x09", "dash-stats first button 0x0A", "dash-stats first telemetry 0F01", "dash-stats first telemetry 0C02"),
            firsts,
        )
        assertEquals(DashStats.Totals(tx = 0, rx = 7, acks = 0, buttons = 3, telemetry = 4, txFailed = 0), stats.totals())
    }

    @Test
    fun `a decode ack is a 09 06 or 09 04 carrying 55, and the packet header is not a TLV`() {
        stats.received(rx(K1GPacket.tlv(0x09, 0x06, 0x55)), nowMs = 1_000)
        stats.received(rx(K1GPacket.tlv(0x09, 0x06, 0x00)), nowMs = 1_000)
        assertEquals(1, stats.totals().acks)
        assertEquals("the header segment (02 01 \"K1G \") is not counted", 2, stats.totals().rx)
    }
}
