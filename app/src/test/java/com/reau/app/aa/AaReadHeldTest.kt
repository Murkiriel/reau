package com.reau.app.aa

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measured before moving the decoder: how long
 * Android Auto's read thread stayed inside the decoder's input in each window — the total and the longest single
 * time — goes on the `aa-stats` line as `readHeld=<total>/<longest>ms`.
 */
class AaReadHeldTest {
    private var nowMs = 10_000L
    private var nanos = 0L
    private val stats = AaStats { nowMs }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A whole picture message (flags 11): type, timestamp, then the picture from its start code. */
    private fun picture(): AapMessage = message(11, bytes(0, 0) + bytes(0, 0, 0, 0, 0, 0, 0x12, 0x34) + bytes(0, 0, 0, 1, 0x65, 1, 2, 3))

    /** A video-channel message with [flags] (9 first piece, 10 last) and [payload], as the read loop hands it over. */
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

    @Test
    fun `the window line says the read thread's total and longest time inside the decoder`() {
        stats.readHeld(2_500_000L)
        stats.readHeld(7_250_000L)
        stats.readHeld(1_000_000L)
        nowMs += 5_000
        val line = stats.windowLine()
        assertTrue(line, line.contains(" readHeld=10.8/7.3ms "))
    }

    @Test
    fun `each window starts again, and a window without pictures says zero`() {
        stats.readHeld(4_000_000L)
        stats.windowLine()
        val line = stats.windowLine()
        assertTrue(line, line.contains(" readHeld=0.0/0.0ms "))
    }

    @Test
    fun `the time measured is the decode call's, on the read thread`() {
        val video = AapVideo(
            decode = { _, _, _ -> nanos += 3_000_000L }, // the decoder holds the thread 3 ms
            onFrameCorrupted = {},
            nowMs = { nowMs },
            stats = stats,
            nanoTime = { nanos },
        )
        video.process(picture())
        nanos += 50_000_000L // time outside the decoder does not count
        video.process(picture())
        val line = stats.windowLine()
        assertTrue(line, line.contains(" readHeld=6.0/3.0ms "))
    }

    @Test
    fun `a picture put back together from its pieces is timed too`() {
        val video = AapVideo(
            decode = { _, _, _ -> nanos += 4_000_000L },
            onFrameCorrupted = {},
            nowMs = { nowMs },
            stats = stats,
            nanoTime = { nanos },
        )
        video.process(message(9, bytes(0, 0) + bytes(0, 0, 0, 0, 0, 0, 0x12, 0x34) + bytes(0, 0, 0, 1, 0x65, 1)))
        video.process(message(10, bytes(2, 3, 4, 5)))
        val line = stats.windowLine()
        assertTrue(line, line.contains(" assembled=1 ") && line.contains(" readHeld=4.0/4.0ms "))
    }
}
