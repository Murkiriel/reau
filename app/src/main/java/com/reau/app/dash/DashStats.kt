package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.dash.protocol.Tlv
import java.util.Locale
import java.util.TreeMap

/**
 * What REau and the dash exchanged, counted at [DashSocket] (every control packet goes through it; a received one
 * where the session reads it, from the same list).
 * Sent packets by their first TLV (a packet is one command, a heartbeat carries several TLVs), received TLVs by kind:
 * decode acks (09 06 or 09 04 carrying 55), buttons (09 00) and telemetry (0C, 0F), each button code and telemetry
 * kind also told once through [onFirst]; the heartbeats, counted by the session's loops; failed and suppressed sends;
 * the time since the dash last spoke. [windowLine] gives one `dash-stats` line per window and starts the next; the
 * totals go on. Counts and codes only: no payload, nothing personal.
 */
class DashStats(private val onFirst: (String) -> Unit = {}) {
    data class Totals(val tx: Long, val rx: Long, val acks: Long, val buttons: Long, val telemetry: Long, val txFailed: Long)

    private class Counts {
        val tx = TreeMap<String, Long>()
        val rx = TreeMap<String, Long>()
        var acks = 0L
        var buttons = 0L
        var telemetry = 0L
        var heartbeats = 0L
        var txFailed = 0L
    }

    private val lock = Any()
    private var window = Counts()
    private val total = Counts()
    private val seen = HashSet<String>()
    private var lastRxMs = -1L

    private inline fun both(action: Counts.() -> Unit) = synchronized(lock) {
        window.action()
        total.action()
    }

    /** A control packet went out. */
    fun sent(packet: ByteArray) {
        val key = sentKey(packet) ?: return
        both { tx.merge(key, 1L, Long::plus) }
    }

    /** A control packet could not go out (a socket error, or held back while the link is down). */
    fun sendFailed() = both { txFailed++ }

    /** One heartbeat sent (status or projection). */
    fun heartbeat() = both { heartbeats++ }

    /** A packet from the dash, at [nowMs], as [K1GPacket.parseIncoming] read it (once, for the session too). */
    fun received(tlvs: List<Tlv>, nowMs: Long) {
        val news = ArrayList<String>()
        synchronized(lock) {
            lastRxMs = nowMs
            for (tlv in tlvs) {
                if (isHeader(tlv)) continue
                both { rx.merge(key(tlv), 1L, Long::plus) }
                classify(tlv)?.let { news += it }
            }
        }
        news.forEach { onFirst("dash-stats first $it") }
    }

    /** Counts [tlv] as an ack, a button or telemetry; the button code or telemetry kind if it is the first of it. */
    private fun classify(tlv: Tlv): String? {
        val isAck = tlv.type == ACK && (tlv.sub == ACK_IDR || tlv.sub == ACK_P) && tlv.value.firstOrNull() == ACK_VALUE
        val kind = when {
            isAck -> {
                both { acks++ }
                null
            }
            tlv.type == ACK && tlv.sub == BUTTON && tlv.value.isNotEmpty() -> {
                both { buttons++ }
                "button 0x%02X".format(Locale.US, tlv.value.last().toInt() and BYTE)
            }
            tlv.type == TELEMETRY_DASH || tlv.type == TELEMETRY_SECURE -> {
                both { telemetry++ }
                "telemetry ${key(tlv)}"
            }
            else -> null
        }
        return kind?.takeIf { seen.add(it) }
    }

    /** The line of the window just closed ([windowMs] long), and a new window. */
    fun windowLine(windowMs: Long, nowMs: Long): String = synchronized(lock) {
        val w = window
        window = Counts()
        val lastRx = if (lastRxMs < 0) "never" else "${nowMs - lastRxMs}ms ago"
        String.format(Locale.US, "dash-stats %.1fs: ", windowMs / MS_PER_S) +
            "tx=${w.tx.values.sum()} (${parts(w.tx)}) rx=${w.rx.values.sum()} (${parts(groupTelemetry(w.rx))}) acks=${w.acks} " +
            "buttons=${w.buttons} telemetry=${w.telemetry} heartbeats=${w.heartbeats} tx-fail=${w.txFailed} " +
            "last-rx=$lastRx | total tx=${total.tx.values.sum()} rx=${total.rx.values.sum()} acks=${total.acks} " +
            "buttons=${total.buttons} telemetry=${total.telemetry} tx-fail=${total.txFailed}"
    }

    fun totals(): Totals = synchronized(lock) {
        Totals(total.tx.values.sum(), total.rx.values.sum(), total.acks, total.buttons, total.telemetry, total.txFailed)
    }

    internal companion object {
        const val MS_PER_S = 1000.0
        const val BYTE = 0xFF
        const val ACK = 0x09
        const val ACK_IDR = 0x06
        const val ACK_P = 0x04
        const val BUTTON = 0x00
        const val ACK_VALUE: Byte = 0x55
        const val TELEMETRY_DASH = 0x0C
        const val TELEMETRY_SECURE = 0x0F
        private val TELEMETRY_FAMILIES = setOf("0C", "0F")

        const val HEADER_TYPE = 0x02
        const val HEADER_SUB = 0x01

        fun key(tlv: Tlv) = "%02X%02X".format(Locale.US, tlv.type, tlv.sub)

        /**
         * The telemetry kinds (0C.., 0F..) come about 25 a window, one each, and filled a fifth of the line;
         * each family is one count (`0C*=25`). The kinds are still told, once, in the `dash-stats first` lines.
         */
        fun groupTelemetry(map: Map<String, Long>): Map<String, Long> {
            val grouped = TreeMap<String, Long>()
            for ((kind, n) in map) {
                val family = kind.take(2)
                grouped.merge(if (family in TELEMETRY_FAMILIES) "$family*" else kind, n, Long::plus)
            }
            return grouped
        }

        fun parts(map: Map<String, Long>) = map.entries.joinToString(" ") { (k, n) -> "$k=$n" }

        /** The fixed header read as a segment by [K1GPacket.parseIncoming] (02 01, "K1G " and the seq). */
        fun isHeader(tlv: Tlv) = tlv.type == HEADER_TYPE && tlv.sub == HEADER_SUB

        // Where K1GPacket.build puts the first TLV after the header, and how much makes one (DashDump.outgoingTlvs).
        private const val FIRST_TLV_AT = 17
        private const val TLV_HEADER = 4
        private const val SEGMENTS_AT = 2

        /**
         * The first TLV's type and sub of a sent packet, read in place, under the conditions
         * [DashDump.outgoingTlvs] had (it copied every TLV only for this): a TLV header's room after the packet header,
         * and the segment count saying there is one past the header segment.
         */
        fun sentKey(packet: ByteArray): String? {
            if (packet.size < FIRST_TLV_AT + TLV_HEADER) return null
            val segments = ((packet[SEGMENTS_AT].toInt() and BYTE) shl 8) or (packet[SEGMENTS_AT + 1].toInt() and BYTE)
            if (segments < 2) return null
            return "%02X%02X".format(Locale.US, packet[FIRST_TLV_AT].toInt() and BYTE, packet[FIRST_TLV_AT + 1].toInt() and BYTE)
        }
    }
}
