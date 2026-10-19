package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.dash.protocol.Tlv

/**
 * A dash packet byte by byte for the detailed log, TLV by TLV (`TTSS:hex`). The route card's title (05 01, text the
 * dash shows) and the key exchange (08 00) are left out with their size kept (no street, no destination, no key in
 * the log). So are the music card (05 0D), the album's picture (05 40) and the call card (05 22): no song, no caller.
 * This takes the place of Dbg's raw hex, which carries them.
 */
object DashDump {
    private const val BYTE = 0xFF

    /** Where the TLVs of a packet built by [K1GPacket.build] start: lengths (4), the fixed header (12), seq (1). */
    private const val OUTGOING_TLVS_AT = 17
    private val LEFT_OUT = setOf(0x05 to 0x01, 0x08 to 0x00, 0x05 to 0x0D, 0x05 to 0x22, 0x05 to 0x40)

    fun outgoing(packet: ByteArray): String = describe(outgoingTlvs(packet))

    fun incoming(packet: ByteArray): String =
        describe(K1GPacket.parseIncoming(packet).filterNot { it.type == 0x02 && it.sub == 0x01 }) // the packet header

    /** The TLVs of a packet in the phone-to-dash layout (the header counts as the first of its segments). */
    internal fun outgoingTlvs(data: ByteArray): List<Tlv> {
        if (data.size < OUTGOING_TLVS_AT) return emptyList()
        val segments = ((data[2].toInt() and BYTE) shl 8) or (data[3].toInt() and BYTE)
        val out = ArrayList<Tlv>()
        var i = OUTGOING_TLVS_AT
        while (out.size < segments - 1 && i + 4 <= data.size) {
            val len = ((data[i + 2].toInt() and BYTE) shl 8) or (data[i + 3].toInt() and BYTE)
            val end = (i + 4 + len).coerceAtMost(data.size)
            out += Tlv(data[i].toInt() and BYTE, data[i + 1].toInt() and BYTE, data.copyOfRange(i + 4, end))
            i = end
        }
        return out
    }

    private fun describe(tlvs: List<Tlv>): String = tlvs.joinToString(" ") { tlv ->
        val key = "%02X%02X".format(tlv.type, tlv.sub)
        val value = if ((tlv.type to tlv.sub) in LEFT_OUT) "<${tlv.value.size}B left out>" else tlv.value.joinToString("") { "%02X".format(it) }
        "$key:$value"
    }
}
