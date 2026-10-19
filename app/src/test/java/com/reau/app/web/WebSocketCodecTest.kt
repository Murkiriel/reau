package com.reau.app.web

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The WebSocket of the web server, RFC 6455: the handshake key, the server's frames and the client's masked ones. */
class WebSocketCodecTest {
    @Test
    fun `the accept key is the one of the RFC example`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `a small binary frame has no mask and a 7 bit length`() {
        val payload = byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(0x82.toByte(), 3, 1, 2, 3), WebSocketCodec.encode(WebSocketCodec.BINARY, payload))
    }

    @Test
    fun `a text frame is opcode 1`() {
        val frame = WebSocketCodec.encode(WebSocketCodec.TEXT, "ok".toByteArray())
        assertEquals(0x81.toByte(), frame[0])
        assertEquals(2.toByte(), frame[1])
    }

    @Test
    fun `125 bytes still fit the 7 bit length, 126 take 16 bits`() {
        assertEquals(125.toByte(), WebSocketCodec.encode(WebSocketCodec.BINARY, ByteArray(125))[1])
        val f = WebSocketCodec.encode(WebSocketCodec.BINARY, ByteArray(126))
        assertEquals(126.toByte(), f[1])
        assertEquals(0, f[2].toInt())
        assertEquals(126, f[3].toInt() and 0xFF)
        assertEquals(4 + 126, f.size)
    }

    @Test
    fun `65536 bytes take 64 bits`() {
        val f = WebSocketCodec.encode(WebSocketCodec.BINARY, ByteArray(65_536))
        assertEquals(127.toByte(), f[1])
        val length = (2..9).fold(0L) { acc, i -> (acc shl 8) or (f[i].toLong() and 0xFF) }
        assertEquals(65_536L, length)
        assertEquals(10 + 65_536, f.size)
    }

    /** A client frame as a browser sends it: FIN, the opcode, the mask bit, the length and a mask over the payload. */
    private fun clientFrame(opcode: Int, payload: ByteArray, mask: ByteArray = byteArrayOf(0x12, 0x34, 0x56, 0x78), fin: Boolean = true): ByteArray {
        val head = mutableListOf<Byte>(((if (fin) 0x80 else 0) or opcode).toByte())
        when {
            payload.size < 126 -> head += (0x80 or payload.size).toByte()
            payload.size < 65_536 -> {
                head += (0x80 or 126).toByte()
                head += (payload.size shr 8).toByte()
                head += payload.size.toByte()
            }
            else -> {
                head += (0x80 or 127).toByte()
                for (i in 7 downTo 0) head += ((payload.size.toLong() shr (8 * i)) and 0xFF).toByte()
            }
        }
        head += mask.toList()
        return head.toByteArray() + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
    }

    private fun read(bytes: ByteArray, max: Int = 4096) = WebSocketCodec.read(ByteArrayInputStream(bytes), max)

    private fun assertViolation(block: () -> Unit) {
        try {
            block()
            fail("expected a WebSocketViolation")
        } catch (e: WebSocketViolation) {
            assertTrue(e.message, !e.message.isNullOrBlank())
        }
    }

    @Test
    fun `a masked text frame of the client is read and unmasked`() {
        val frame = read(clientFrame(WebSocketCodec.TEXT, "{\"a\":\"d\"}".toByteArray()))!!
        assertEquals(WebSocketCodec.TEXT, frame.opcode)
        assertEquals("{\"a\":\"d\"}", String(frame.payload))
    }

    @Test
    fun `a client frame of 300 bytes with a 16 bit length is read`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        assertArrayEquals(payload, read(clientFrame(WebSocketCodec.BINARY, payload))!!.payload)
    }

    @Test
    fun `a ping, a pong and a close are read as control frames`() {
        assertEquals(WebSocketCodec.PING, read(clientFrame(WebSocketCodec.PING, byteArrayOf(9)))!!.opcode)
        assertEquals(WebSocketCodec.PONG, read(clientFrame(WebSocketCodec.PONG, ByteArray(0)))!!.opcode)
        assertEquals(WebSocketCodec.CLOSE, read(clientFrame(WebSocketCodec.CLOSE, byteArrayOf(0x03, 0xE8.toByte())))!!.opcode)
    }

    @Test
    fun `the end of the stream between frames is null, in the middle of one is an error`() {
        assertNull(read(ByteArray(0)))
        val whole = clientFrame(WebSocketCodec.TEXT, "hello".toByteArray())
        assertViolation { read(whole.copyOf(whole.size - 2)) }
        assertViolation { read(byteArrayOf(0x81.toByte())) }
    }

    @Test
    fun `a frame with no mask is a violation`() {
        assertViolation { read(byteArrayOf(0x81.toByte(), 2, 'o'.code.toByte(), 'k'.code.toByte())) }
    }

    @Test
    fun `a payload over the limit is a violation`() {
        assertViolation { read(clientFrame(WebSocketCodec.BINARY, ByteArray(5000)), max = 4096) }
        assertEquals(4096, read(clientFrame(WebSocketCodec.BINARY, ByteArray(4096)), max = 4096)!!.payload.size)
    }

    @Test
    fun `a 64 bit length that is huge is a violation, never an allocation`() {
        val huge = byteArrayOf(0x82.toByte(), (0x80 or 127).toByte(), 0x7F, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0)
        assertViolation { read(huge) }
        val negative = byteArrayOf(0x82.toByte(), (0x80 or 127).toByte(), -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0)
        assertViolation { read(negative) }
    }

    @Test
    fun `a fragmented message is a violation, and so is a long control frame`() {
        assertViolation { read(clientFrame(WebSocketCodec.TEXT, "ab".toByteArray(), fin = false)) }
        assertViolation { read(clientFrame(WebSocketCodec.PING, ByteArray(126))) }
        assertViolation { read(clientFrame(WebSocketCodec.PING, "x".toByteArray(), fin = false)) }
    }

    @Test
    fun `an opcode the server does not know is a violation`() {
        assertViolation { read(clientFrame(0x3, "x".toByteArray())) }
        assertViolation { read(clientFrame(0xB, "x".toByteArray())) }
    }

    @Test
    fun `reserved bits set are a violation`() {
        val frame = clientFrame(WebSocketCodec.TEXT, "x".toByteArray())
        frame[0] = (frame[0].toInt() or 0x40).toByte()
        assertViolation { read(frame) }
    }

    @Test
    fun `two frames in a row are read one after the other`() {
        val both = clientFrame(WebSocketCodec.TEXT, "one".toByteArray()) + clientFrame(WebSocketCodec.TEXT, "two".toByteArray())
        val input = ByteArrayInputStream(both)
        assertEquals("one", String(WebSocketCodec.read(input, 4096)!!.payload))
        assertEquals("two", String(WebSocketCodec.read(input, 4096)!!.payload))
        assertNull(WebSocketCodec.read(input, 4096))
    }
}
