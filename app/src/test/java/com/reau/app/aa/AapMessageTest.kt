package com.reau.app.aa

import com.reau.app.aa.proto.Common
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import com.reau.app.aa.proto.Sensors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation of the Android Auto message layer copied from OpenCfMoto (which ported it from
 * headunit-revived): written AFTER the code, to pin down byte by byte what it puts on the wire and reads back.
 * The expected bytes are worked out by hand from the frame layout and the protobuf field numbers, not captured
 * from a run.
 *
 * A frame on the wire: channel, flags, payload length (2 bytes, big-endian), then the payload: message type
 * (2 bytes, big-endian) and the protobuf body.
 */
class AapMessageTest {

    // ── out ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the version request is the ten bytes every head unit opens with`() {
        // channel 0, flags 3, length 6, type 1 (version request), then major 1 and minor 2 as two 16-bit numbers.
        assertArrayEquals(bytes(0, 3, 0, 6, 0, 1, 0, 1, 0, 2), Messages.versionRequest)
    }

    @Test
    fun `the newer protocol test asks for 1 dot 7 and the usual one stays 1 dot 2`() {
        assertArrayEquals(bytes(0, 3, 0, 6, 0, 1, 0, 1, 0, 7), Messages.versionRequestFor(Messages.MINOR_NEWER))
        assertArrayEquals(Messages.versionRequest, Messages.versionRequestFor(Messages.MINOR_DEFAULT))
        assertEquals(2, Messages.MINOR_DEFAULT)
        assertEquals(7, Messages.MINOR_NEWER)
    }

    @Test
    fun `the version Android Auto answers is read for the log`() {
        // channel 0, flags 3, length 8, type 2 (version response), major 1, minor 7, status 0.
        val answer = bytes(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
        assertEquals("1.7, status 0", Messages.versionAnswer(answer, answer.size))
        val refused = bytes(0, 3, 0, 8, 0, 2, 0, 1, 0, 2, 0xFF, 0xFF)
        assertEquals("1.2, status -1", Messages.versionAnswer(refused, refused.size))
        assertEquals("too short", Messages.versionAnswer(answer, 6))
    }

    @Test
    fun `status OK after the TLS handshake is eight bytes`() {
        // channel 0, flags 3, length 4, type 4 (auth complete), then protobuf field 1 = 0.
        assertArrayEquals(bytes(0, 3, 0, 4, 0, 4, 8, 0), Messages.statusOk)
    }

    @Test
    fun `a raw message is header, type and the data as given`() {
        assertArrayEquals(bytes(3, 0x0B, 0, 5, 0x80, 0x01, 1, 2, 3), Messages.createRawMessage(3, 0x0B, 0x8001, bytes(1, 2, 3)))
        assertArrayEquals(bytes(0, 3, 0, 2, 0, 3), Messages.createRawMessage(0, 3, 3, ByteArray(0)))
    }

    @Test
    fun `a media ack is channel, flags 0B, length, type 8004 and the two protobuf fields`() {
        val ack = MediaAck(Channel.ID_VID, sessionId = 7)
        // session_id (field 1) = 7, ack (field 2) = 1.
        assertArrayEquals(bytes(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1), ack.data)
        assertEquals(Channel.ID_VID, ack.channel)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack.type)
        assertEquals("the protobuf body starts after the header and the type", 6, ack.dataOffset)
        assertEquals("the whole frame", 10, ack.size)
    }

    @Test
    fun `video focus says projected or native, and whether Android Auto asked`() {
        // mode (field 1): 1 = projected, 2 = native; unsolicited (field 2).
        assertArrayEquals(bytes(2, 0x0B, 0, 6, 0x80, 0x08, 0x08, 1, 0x10, 0), VideoFocusEvent(gain = true, unsolicited = false).data)
        assertArrayEquals(bytes(2, 0x0B, 0, 6, 0x80, 0x08, 0x08, 2, 0x10, 1), VideoFocusEvent(gain = false, unsolicited = true).data)
    }

    @Test
    fun `the driving status goes on the sensor channel as field 13 of the sensor batch`() {
        // SensorBatch.driving_status (field 13, a nested message of 2 bytes): status (field 1).
        val unrestricted = DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED)
        assertArrayEquals(bytes(1, 0x0B, 0, 6, 0x80, 0x03, 0x6A, 2, 0x08, 0), unrestricted.data)
        val noVideo = DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.NO_VIDEO)
        assertArrayEquals(bytes(1, 0x0B, 0, 6, 0x80, 0x03, 0x6A, 2, 0x08, 1), noVideo.data)
    }

    @Test
    fun `night mode goes on the sensor channel as field 10 of the sensor batch`() {
        assertArrayEquals(bytes(1, 0x0B, 0, 6, 0x80, 0x03, 0x52, 2, 0x08, 1), NightModeEvent(isNight = true).data)
        assertArrayEquals(bytes(1, 0x0B, 0, 6, 0x80, 0x03, 0x52, 2, 0x08, 0), NightModeEvent(isNight = false).data)
    }

    @Test
    fun `a control-type message on another channel is flagged 0F, on the control channel 0B`() {
        val ok = Control.ChannelOpenResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build()
        val type = Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE // 8
        assertArrayEquals(bytes(2, 0x0F, 0, 4, 0, 8, 0x08, 0), AapMessage(Channel.ID_VID, type, ok).data)
        assertArrayEquals(bytes(0, 0x0B, 0, 4, 0, 8, 0x08, 0), AapMessage(Channel.ID_CTR, type, ok).data)
    }

    @Test
    fun `a body longer than 255 bytes has its length in two bytes`() {
        val ping = Control.PingRequest.newBuilder().setTimestamp(1).build() // 2 bytes
        assertEquals(4, AapMessage(Channel.ID_CTR, 11, ping).data[3].toInt())
        val request = Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("x".repeat(300)).setPhoneBrand("y").build()
        val message = AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE, request)
        val length = request.serializedSize + 2
        assertTrue("this test needs a body over 255 bytes, got $length", length > 255)
        assertEquals(length shr 8, message.data[2].toInt() and 0xFF)
        assertEquals(length and 0xFF, message.data[3].toInt() and 0xFF)
        assertEquals(length + 4, message.data.size)
    }

    // ── in ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Returns what it is told to, and remembers what it was asked to decrypt. */
    private class FakeSsl(private val plain: ByteArrayWithLimit?) : AapSsl {
        var asked: Triple<Int, Int, ByteArray>? = null
        override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
            asked = Triple(start, length, buffer)
            return plain
        }
        override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? = null
        override fun postHandshakeReset() = Unit
        override fun performHandshake(connection: AccessoryConnection): Boolean = false
        override fun release() = Unit
    }

    private fun header(vararg wire: Int) = AapMessageIncoming.EncryptedHeader().apply {
        bytes(*wire).copyInto(buf)
        decode()
    }

    @Test
    fun `the four header bytes are channel, flags and the encrypted length`() {
        val h = header(2, 0x0B, 0x01, 0x2C)
        assertEquals(2, h.chan)
        assertEquals(0x0B, h.flags)
        assertEquals(300, h.encLen)
        assertEquals(4, AapMessageIncoming.EncryptedHeader.SIZE)
    }

    @Test
    fun `an incoming frame goes through decryption and comes out as the message that was sent`() {
        val sent = MediaAck(Channel.ID_VID, sessionId = 7).data // 02 0B 00 06 | 80 04 08 07 10 01
        val h = header(sent[0].toInt(), sent[1].toInt(), sent[2].toInt(), sent[3].toInt())
        val encrypted = ByteArray(64) { 0x55 }
        val ssl = FakeSsl(ByteArrayWithLimit(sent.copyOfRange(4, 10) + ByteArray(20), limit = 6)) // a larger buffer, 6 valid

        val message = checkNotNull(AapMessageIncoming.decrypt(h, 9, encrypted, ssl))

        assertEquals("decryption is asked for exactly the frame's bytes", Triple(9, 6, encrypted), ssl.asked)
        assertEquals(Channel.ID_VID, message.channel)
        assertEquals(0x0B.toByte(), message.flags)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, message.type)
        assertEquals("the body starts after the two type bytes", 2, message.dataOffset)
        assertEquals("the valid bytes, not the buffer's size", 6, message.size)
        val ack = message.parse(Media.Ack.newBuilder()).build()
        assertEquals(7, ack.sessionId)
        assertEquals(1, ack.ack)
    }

    @Test
    fun `a frame that is not flagged as encrypted is refused without touching the decryption`() {
        val ssl = FakeSsl(ByteArrayWithLimit(bytes(0x80, 0x04), 2))
        assertNull(AapMessageIncoming.decrypt(header(2, 0x03, 0, 6), 0, ByteArray(8), ssl))
        assertNull(ssl.asked)
    }

    @Test
    fun `a frame that fails to decrypt, or decrypts to less than a type, is no message`() {
        assertNull(AapMessageIncoming.decrypt(header(2, 0x0B, 0, 6), 0, ByteArray(8), FakeSsl(null)))
        assertNull(AapMessageIncoming.decrypt(header(2, 0x0B, 0, 6), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(bytes(0x80), 1))))
    }

    @Test
    fun `a one byte tail of a split video frame is a message, the rest of the frame needs it`() {
        // flags 0x0A = encrypted + last piece: raw continuation bytes, no message type, and this one is a single byte
        val last = AapMessageIncoming.decrypt(header(Channel.ID_VID, 0x0A, 0, 1), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(bytes(0x7F), 1)))
        assertNotNull("a one byte last piece is kept", last)
        assertEquals(1, last!!.size)
        assertEquals(0x7F, last.data[0].toInt())
        // flags 0x08 = encrypted + middle piece
        assertNotNull(AapMessageIncoming.decrypt(header(Channel.ID_VID, 0x08, 0, 1), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(bytes(0x7F), 1))))
        // nothing at all is still nothing
        assertNull(AapMessageIncoming.decrypt(header(Channel.ID_VID, 0x0A, 0, 0), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(ByteArray(0), 0))))
        // a first piece or a whole message still needs its two type bytes, and so does any other channel
        assertNull(AapMessageIncoming.decrypt(header(Channel.ID_VID, 0x09, 0, 1), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(bytes(0x7F), 1))))
        assertNull(AapMessageIncoming.decrypt(header(Channel.ID_AUD, 0x0A, 0, 1), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(bytes(0x7F), 1))))
    }

    // ── identity ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two messages are equal when channel, flags, type and bytes are`() {
        assertEquals(MediaAck(Channel.ID_VID, 7), MediaAck(Channel.ID_VID, 7))
        assertEquals(MediaAck(Channel.ID_VID, 7).hashCode(), MediaAck(Channel.ID_VID, 7).hashCode())
        assertNotEquals(MediaAck(Channel.ID_VID, 7), MediaAck(Channel.ID_VID, 8))
        assertNotEquals(MediaAck(Channel.ID_VID, 7), MediaAck(Channel.ID_AUD, 7))
        val ack: AapMessage = MediaAck(Channel.ID_VID, 7)
        val focus: AapMessage = VideoFocusEvent(gain = true, unsolicited = false)
        assertNotEquals(ack, focus)
        assertFalse(MediaAck(Channel.ID_VID, 7).equals("a string"))

        // A received message does not carry its channel in its bytes: the same payload on two channels is two messages.
        fun received(channel: Int) = AapMessageIncoming.decrypt(
            header(channel, 0x0B, 0, 6), 0, ByteArray(8), FakeSsl(ByteArrayWithLimit(bytes(0x80, 0x04, 0x08, 7, 0x10, 1), 6)),
        )
        assertEquals(received(Channel.ID_VID), received(Channel.ID_VID))
        assertNotEquals(received(Channel.ID_VID), received(Channel.ID_AUD))
    }

    @Test
    fun `a message describes itself by channel and type names`() {
        assertEquals("VIDEO Codec/Media Data Ack type: 32772 flags: 11 size: 10 dataOffset: 6", MediaAck(Channel.ID_VID, 7).toString())
    }

    @Test
    fun `video and audio channels are told apart`() {
        assertTrue(MediaAck(Channel.ID_VID, 1).isVideo)
        assertFalse(MediaAck(Channel.ID_VID, 1).isAudio)
        assertTrue(MediaAck(Channel.ID_AU1, 1).isAudio)
        assertFalse(MediaAck(Channel.ID_AU1, 1).isVideo)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
