package com.reau.app.aa

import java.io.ByteArrayOutputStream

/**
 * Puts back together a message Android Auto sent in pieces on one channel. The video channel has
 * its own ([VideoFragmentAssembler]); this one is for the small channels (music, phone), where a song's album art or a
 * caller's picture can split one message in several. The header flags say which piece it is, as in open-headunit's
 * aap/AapMediaPlayback.kt (AGPL-3.0, commit 93c065dd): first (0x01) carries the message type, the middle ones and the
 * last (0x02) only the rest of the payload; both bits, a whole message.
 *
 * A piece out of order (a middle or last one with no first) is dropped, and so is a message larger than [maxBytes].
 */
internal class AapFragments(private val maxBytes: Int = MAX_BYTES) {

    /** A whole message: its type and its payload (the protobuf, without the two type bytes). */
    class Whole(val type: Int, val payload: ByteArray)

    private val buffer = ByteArrayOutputStream()
    private var type = -1
    private var collecting = false

    /** The whole message once [message] completes one; null while pieces are missing or when one is dropped. */
    fun take(message: AapMessage): Whole? {
        val piece = message.flags.toInt() and (FIRST or LAST)
        return when (piece) {
            FIRST or LAST -> {
                collecting = false
                Whole(message.type, message.data.copyOfRange(message.dataOffset, message.size))
            }
            FIRST -> {
                buffer.reset()
                type = message.type
                collecting = append(message.data, message.dataOffset, message.size)
                null
            }
            LAST -> {
                val whole = if (collecting && append(message.data, 0, message.size)) Whole(type, buffer.toByteArray()) else null
                collecting = false
                buffer.reset()
                whole
            }
            else -> {
                if (collecting) collecting = append(message.data, 0, message.size)
                null
            }
        }
    }

    /** Adds bytes [from] until [until] of [data]; false (and the message dropped) when it would pass [maxBytes]. */
    private fun append(data: ByteArray, from: Int, until: Int): Boolean {
        val length = until - from
        if (length < 0 || buffer.size() + length > maxBytes) {
            AaLog.w("AapFragments: message over %d bytes or broken, dropped", maxBytes)
            buffer.reset()
            return false
        }
        buffer.write(data, from, length)
        return true
    }

    companion object {
        const val FIRST = 0x01
        const val LAST = 0x02

        /** open-headunit's buffer for album art. */
        const val MAX_BYTES = 1024 * 1024
    }
}
