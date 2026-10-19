package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sound Android Auto sends: the PCM of a whole data message on the media or the guidance channel, or nothing. */
class AaAudioFeedTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A decrypted message as the read loop hands it over: [channel], [flags] and the payload (type, timestamp?, data). */
    private fun message(channel: Int, flags: Int, payload: ByteArray): AapMessage {
        val header = AapMessageIncoming.EncryptedHeader().apply {
            bytes(channel, flags, payload.size shr 8, payload.size and 0xFF).copyInto(buf)
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

    private val timestamp = bytes(0, 0, 0, 0, 0, 0, 0x12, 0x34)
    private val pcm = bytes(1, 2, 3, 4, 5, 6, 7, 8)
    private val dataWithTimestamp = bytes(0, 0) + timestamp + pcm
    private val whole = 11

    @Test
    fun `the media channel gives its PCM after the type and the timestamp, as 48 kHz stereo`() {
        val got = checkNotNull(AaAudioFeed.pcm(message(Channel.ID_AUD, whole, dataWithTimestamp)))
        assertEquals(AaAudioFeed.Stream.MEDIA, got.stream)
        assertArrayEquals(pcm, got.data)
        assertEquals(48_000, got.stream.sampleRate)
        assertEquals(2, got.stream.channels)
    }

    @Test
    fun `the guidance channel gives its PCM as 16 kHz mono`() {
        val got = checkNotNull(AaAudioFeed.pcm(message(Channel.ID_AU1, whole, dataWithTimestamp)))
        assertEquals(AaAudioFeed.Stream.GUIDANCE, got.stream)
        assertArrayEquals(pcm, got.data)
        assertEquals(16_000, got.stream.sampleRate)
        assertEquals(1, got.stream.channels)
    }

    @Test
    fun `the speech channel gives its PCM as 16 kHz mono, as its own stream`() {
        val got = checkNotNull(AaAudioFeed.pcm(message(Channel.ID_SPE, whole, dataWithTimestamp)))
        assertEquals(AaAudioFeed.Stream.SPEECH, got.stream)
        assertArrayEquals(pcm, got.data)
        assertEquals(16_000, got.stream.sampleRate)
        assertEquals(1, got.stream.channels)
    }

    @Test
    fun `the system sounds, the video and the control are not sound for the browser`() {
        assertNull(AaAudioFeed.pcm(message(Channel.ID_AU2, whole, dataWithTimestamp)))
        assertNull(AaAudioFeed.pcm(message(Channel.ID_VID, whole, dataWithTimestamp)))
        assertNull(AaAudioFeed.pcm(message(Channel.ID_CTR, whole, dataWithTimestamp)))
    }

    @Test
    fun `a codec configuration, a message with no data and one that is only a piece give nothing`() {
        assertNull("type 1 is the codec's own data", AaAudioFeed.pcm(message(Channel.ID_AUD, whole, bytes(0, 1) + pcm)))
        assertNull("only the type and the time", AaAudioFeed.pcm(message(Channel.ID_AUD, whole, bytes(0, 0) + timestamp)))
        for (piece in listOf(9, 8, 10)) assertNull("flags $piece", AaAudioFeed.pcm(message(Channel.ID_AUD, piece, dataWithTimestamp)))
    }

    @Test
    fun `an odd number of bytes loses the half sample, never the sample before`() {
        val odd = bytes(0, 0) + timestamp + bytes(1, 2, 3, 4, 5)
        val got = checkNotNull(AaAudioFeed.pcm(message(Channel.ID_AU1, whole, odd)))
        assertArrayEquals(bytes(1, 2, 3, 4), got.data)
    }

    @Test
    fun `the PCM is a copy, so the read buffer can be reused`() {
        val msg = message(Channel.ID_AUD, whole, dataWithTimestamp)
        val got = checkNotNull(AaAudioFeed.pcm(msg))
        msg.data.fill(0)
        assertArrayEquals(pcm, got.data)
    }
}
