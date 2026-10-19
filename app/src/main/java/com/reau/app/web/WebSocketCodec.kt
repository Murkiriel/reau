package com.reau.app.web

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** The client broke the WebSocket protocol (or the stream ended in the middle of a frame): the connection is dropped. */
class WebSocketViolation(message: String) : Exception(message)

/** One whole message of the client: no fragments are accepted. */
class WebSocketFrame(val opcode: Int, val payload: ByteArray)

/**
 * The WebSocket of the web server, RFC 6455 and nothing more than the page needs: the server sends unmasked
 * binary and text frames of any size; it reads masked, unfragmented frames of at most a few KB (the touch, a ping, a close).
 */
object WebSocketCodec {
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_CONTROL = 125
    private val KNOWN = setOf(TEXT, BINARY, CLOSE, PING, PONG)

    /** The `Sec-WebSocket-Accept` that answers the client's `Sec-WebSocket-Key`. */
    fun acceptKey(clientKey: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((clientKey + GUID).toByteArray()))

    /** A frame from the server: FIN set, no mask, the length in 7, 16 or 64 bits. */
    fun encode(opcode: Int, payload: ByteArray): ByteArray {
        val size = payload.size
        val head = when {
            size <= MAX_CONTROL -> byteArrayOf((0x80 or opcode).toByte(), size.toByte())
            size < 65_536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (size shr 8).toByte(), size.toByte())
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte()
                it[1] = 127
                for (i in 0 until 8) it[2 + i] = ((size.toLong() shr (8 * (7 - i))) and 0xFF).toByte()
            }
        }
        return head + payload
    }

    /**
     * The next frame of the client, unmasked; null when the stream ends between frames. A frame that is unmasked, fragmented,
     * of an unknown opcode, with reserved bits, a control frame over 125 bytes or a payload over [maxPayload] is a
     * [WebSocketViolation], and so is a stream that ends inside a frame.
     */
    fun read(input: InputStream, maxPayload: Int): WebSocketFrame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) throw WebSocketViolation("frame cut short")
        val opcode = first and 0x0F
        demand((first and 0x70) == 0) { "reserved bits" }
        demand(opcode in KNOWN) { "opcode $opcode" }
        demand((first and 0x80) != 0) { "fragmented" }
        demand((second and 0x80) != 0) { "unmasked" }
        val length = lengthOf(input, second and 0x7F)
        demand(length in 0L..maxPayload.toLong()) { "payload of $length bytes" }
        demand(opcode < CLOSE || length <= MAX_CONTROL) { "control frame of $length bytes" }
        val mask = readFully(input, 4)
        val payload = readFully(input, length.toInt())
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return WebSocketFrame(opcode, payload)
    }

    private fun lengthOf(input: InputStream, seven: Int): Long = when (seven) {
        126 -> readFully(input, 2).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        127 -> readFully(input, 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        else -> seven.toLong()
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = input.read(bytes, done, count - done)
            if (n < 0) throw WebSocketViolation("frame cut short")
            done += n
        }
        return bytes
    }

    private inline fun demand(ok: Boolean, why: () -> String) {
        if (!ok) throw WebSocketViolation(why())
    }
}
