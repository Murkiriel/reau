package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto's video messages put back together into H.264 access units for the decoder, and a keyframe asked
 * for whenever the run of fragments breaks.
 *
 * A video message is whole (flags 11) or cut in pieces: 9 first, 8 in the middle, 10 last. The first piece starts
 * with the message type and, for picture data, an 8-byte timestamp; the pieces after it are plain continuation.
 */
class AapVideoTest {
    private val decoded = mutableListOf<ByteArray>()
    private var keyframeRequests = 0
    private var nowMs = 10_000L
    private val stats = AaStats { nowMs }

    private fun video(bufferBytes: Int = 4_096) = AapVideo(
        decode = { buffer, offset, size -> decoded += buffer.copyOfRange(offset, offset + size) },
        onFrameCorrupted = { keyframeRequests++ },
        bufferBytes = bufferBytes,
        nowMs = { nowMs },
        stats = stats,
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A decrypted video-channel message as the read loop hands it over: [flags] and the payload. */
    private fun message(flags: Int, payload: ByteArray): AapMessage {
        val header = AapMessageIncoming.EncryptedHeader().apply {
            bytes(Channel.ID_VID, flags, payload.size shr 8, payload.size and 0xFF).copyInto(buf)
            decode()
        }
        val passThrough = object : AapSsl {
            override fun decrypt(start: Int, length: Int, buffer: ByteArray) = ByteArrayWithLimit(payload, payload.size)
            override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? = null
            override fun postHandshakeReset() = Unit
            override fun performHandshake(connection: AccessoryConnection) = true
            override fun release() = Unit
        }
        return checkNotNull(AapMessageIncoming.decrypt(header, 0, payload, passThrough))
    }

    private val startCode = bytes(0, 0, 0, 1)
    private val timestamp = bytes(0, 0, 0, 0, 0, 0, 0x12, 0x34)
    private val dataType = bytes(0, 0) // media data: a timestamp follows
    private val configType = bytes(0, 1) // codec configuration: no timestamp

    // ── whole messages ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a whole picture message goes to the decoder from its start code, after type and timestamp`() {
        val v = video()
        val picture = startCode + bytes(0x65, 1, 2, 3)

        assertTrue("the message was video", v.process(message(11, dataType + timestamp + picture)))
        assertArrayEquals(picture, decoded.single())
        assertEquals(0, keyframeRequests)
    }

    @Test
    fun `a codec configuration message has no timestamp and goes to the decoder from after its type`() {
        val v = video()
        // A real SPS (from the emulator's encoder) and PPS. Where the H.264 starts is found by looking for a start
        // code at offset 10 first and at offset 2 after, as both projects this comes from do; an SPS of exactly
        // four bytes would put the PPS's start code at offset 10 and be misread, and no real SPS is that short.
        val config = startCode + bytes(0x67, 0x42, 0x00, 0x29, 0x8D, 0x68, 0x08, 0x42, 0x7E, 0xAE) + startCode + bytes(0x68, 0xCE, 0x01, 0xA8)

        assertTrue(v.process(message(11, configType + config)))
        assertArrayEquals(config, decoded.single())
    }

    @Test
    fun `a three-byte start code is found as well`() {
        val v = video()
        val picture = bytes(0, 0, 1, 0x41, 9, 9)
        assertTrue(v.process(message(11, dataType + timestamp + picture)))
        assertArrayEquals(picture, decoded.single())
    }

    @Test
    fun `a whole message that is not picture is left for the control layer`() {
        val v = video()
        // Media setup request on the video channel: type 0x8000 and a protobuf body. No start code anywhere.
        assertFalse(v.process(message(11, bytes(0x80, 0x00, 0x08, 0x03))))
        assertTrue(decoded.isEmpty())
        assertEquals("that is no accident of the stream", 0, keyframeRequests)
    }

    // ── pieces ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `first, middle and last piece become one access unit when the last arrives`() {
        val v = video()
        val head = startCode + bytes(0x65, 1, 2)

        assertTrue(v.process(message(9, dataType + timestamp + head)))
        assertTrue(v.process(message(8, bytes(3, 4, 5))))
        assertTrue("nothing is decoded before the last piece", decoded.isEmpty())
        assertTrue(v.process(message(10, bytes(6, 7))))

        assertArrayEquals(head + bytes(3, 4, 5, 6, 7), decoded.single())
        assertEquals(0, keyframeRequests)
    }

    @Test
    fun `one frame after another, each is its own access unit`() {
        val v = video()
        v.process(message(9, dataType + timestamp + startCode + bytes(0x65, 1)))
        v.process(message(10, bytes(2, 2)))
        v.process(message(11, dataType + timestamp + startCode + bytes(0x41, 3)))
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 4)))
        v.process(message(10, bytes(5, 5)))

        assertEquals(
            listOf(listOf(0, 0, 0, 1, 0x65, 1, 2, 2), listOf(0, 0, 0, 1, 0x41, 3), listOf(0, 0, 0, 1, 0x41, 4, 5, 5)),
            decoded.map { frame -> frame.map { it.toInt() and 0xFF } },
        )
    }

    // ── a piece goes missing ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a frame whose last piece never came is dropped, a keyframe is asked for, and the next frame is fine`() {
        val v = video()
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1)))
        v.process(message(8, bytes(2, 2)))
        // The 10 is lost. The next frame begins.
        assertTrue(v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 7))))
        assertEquals("the stream broke: ask for a keyframe", 1, keyframeRequests)
        assertTrue(v.process(message(10, bytes(8, 8))))

        assertArrayEquals("only the whole frame reaches the decoder", startCode + bytes(0x41, 7, 8, 8), decoded.single())
    }

    @Test
    fun `pieces with no first piece are thrown away and a keyframe is asked for`() {
        val v = video()
        assertTrue("still video: the control layer must not see it", v.process(message(8, bytes(1, 2))))
        assertTrue(v.process(message(10, bytes(3, 4))))

        assertTrue("half a frame must never reach the decoder", decoded.isEmpty())
        assertEquals("asked once, not once per piece", 1, keyframeRequests)
    }

    @Test
    fun `a first piece with no start code closes the run, so its followers are thrown away too`() {
        val v = video()
        assertFalse(v.process(message(9, bytes(0x80, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))))
        assertTrue(v.process(message(8, bytes(1, 1))))
        assertTrue(v.process(message(10, bytes(2, 2))))

        assertTrue(decoded.isEmpty())
        assertEquals(1, keyframeRequests)
    }

    @Test
    fun `a frame that outgrows the buffer is dropped with a keyframe request, and the next one is fine`() {
        val v = video(bufferBytes = 16)
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1, 2, 3))) // 8 bytes
        v.process(message(8, ByteArray(6) { 0x55 })) // 14
        assertTrue(v.process(message(8, ByteArray(6) { 0x66 }))) // would be 20: over
        assertEquals(1, keyframeRequests)
        assertTrue(v.process(message(10, bytes(9, 9))))
        assertTrue("the oversized frame never reaches the decoder", decoded.isEmpty())

        v.process(message(11, dataType + timestamp + startCode + bytes(0x65, 1)))
        assertArrayEquals(startCode + bytes(0x65, 1), decoded.single())
    }

    @Test
    fun `a frame whose last piece is the one that does not fit is dropped as well`() {
        val v = video(bufferBytes = 16)
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1, 2, 3))) // 8 bytes
        assertTrue(v.process(message(10, ByteArray(10) { 0x55 }))) // would be 18: over

        assertTrue("a frame with its end cut off must not reach the decoder", decoded.isEmpty())
        assertEquals(1, keyframeRequests)
    }

    @Test
    fun `a frame that fills the buffer to the last byte is whole`() {
        val v = video(bufferBytes = 16)
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1, 2, 3))) // 8 bytes
        v.process(message(10, ByteArray(8) { 0x55 })) // 16

        assertArrayEquals(startCode + bytes(0x41, 1, 2, 3) + ByteArray(8) { 0x55 }, decoded.single())
        assertEquals(0, keyframeRequests)
    }

    @Test
    fun `a start code with nothing behind it is not a picture`() {
        val v = video()
        assertFalse(v.process(message(11, dataType + timestamp + startCode)))
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `keyframe requests are at least a second apart`() {
        val v = video()
        v.process(message(10, bytes(1, 1))) // an orphan
        assertEquals(1, keyframeRequests)

        nowMs += 600
        v.process(message(10, bytes(1, 1)))
        assertEquals("too soon: asking again would only make the phone rebuild the stream twice", 1, keyframeRequests)

        nowMs += 600 // 1.2 s after the first
        v.process(message(10, bytes(1, 1)))
        assertEquals(2, keyframeRequests)
    }

    @Test
    fun `release forgets a frame in progress without counting it as a broken stream`() {
        val v = video()
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1)))
        v.release()
        v.process(message(11, dataType + timestamp + startCode + bytes(0x65, 2)))

        assertArrayEquals(startCode + bytes(0x65, 2), decoded.single())
        assertEquals(0, keyframeRequests)
    }

    // ── counted for the aa-stats line ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a whole keyframe and a picture in pieces are counted as units, with their bytes and the pieces`() {
        val v = video()
        v.process(message(11, dataType + timestamp + startCode + bytes(0x65, 1, 2, 3))) // 8 bytes of H.264
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1)))
        v.process(message(8, bytes(2, 2)))
        v.process(message(10, bytes(3, 3, 3)))
        val t = stats.totals()
        assertEquals("units", 2, t.units)
        assertEquals("keyframes", 1, t.keyframes)
        assertEquals("assembled", 1, t.assembled)
        assertEquals("lost", 0, t.lost)
        nowMs += 5_000
        val line = stats.windowLine()
        assertTrue(line, line.contains(" units=2 bytes=0KB key=1 pieces=3 assembled=1 lost=0 "))
    }

    @Test
    fun `a frame without its first piece is one lost frame, however many pieces follow`() {
        val v = video()
        v.process(message(8, bytes(1, 2)))
        v.process(message(8, bytes(3, 4)))
        v.process(message(10, bytes(5, 6)))
        v.process(message(11, dataType + timestamp + startCode + bytes(0x41, 1)))
        assertEquals(1, stats.totals().lost)
        assertEquals(1, stats.totals().units)
    }

    @Test
    fun `a frame without its last piece is one lost frame`() {
        val v = video()
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1)))
        v.process(message(8, bytes(2, 2)))
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 7)))
        v.process(message(10, bytes(8, 8)))
        assertEquals(1, stats.totals().lost)
        assertEquals(1, stats.totals().assembled)
    }

    @Test
    fun `a first piece with no start code and a frame that outgrows the buffer are lost frames too`() {
        val v = video(bufferBytes = 16)
        v.process(message(9, bytes(0x80, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        v.process(message(8, bytes(1, 1)))
        v.process(message(10, bytes(2, 2)))
        assertEquals("headless", 1, stats.totals().lost)
        v.process(message(9, dataType + timestamp + startCode + bytes(0x41, 1, 2, 3)))
        v.process(message(8, ByteArray(6) { 0x55 }))
        v.process(message(8, ByteArray(6) { 0x66 }))
        v.process(message(10, bytes(9, 9)))
        assertEquals("and the oversized one", 2, stats.totals().lost)
        assertEquals(0, stats.totals().units)
    }
}
