package com.reau.app.aa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.Message
import com.reau.app.aa.proto.Common
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gemini's microphone in a whole session against the fake Android Auto (behind the
 * switch "Gemini pelo joystick"): with a voice given to the session, a request to open is answered with success and
 * the voice goes up the microphone channel until Android Auto closes it. Without one, AapControlTest's
 * theMicrophoneIsNeverOpenedAndSaysSo still holds.
 */
@RunWith(AndroidJUnit4::class)
class AapMicrophoneTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var peer: FakeAndroidAutoPeer
    private lateinit var connection: SocketAccessoryConnection
    private lateinit var transport: AapTransport
    private lateinit var microphone: AaMicrophone

    /** A voice of 20 ms chunks of a steady value, about real time; counts opens and closes. */
    private val opens = AtomicInteger()
    private val closes = AtomicInteger()
    private val voice = object : VoiceSource {
        @Volatile var open = false
        override fun open(): Boolean = true.also { open = true; opens.incrementAndGet() }
        override fun read(into: ShortArray): Int {
            if (!open) return -1
            Thread.sleep(20)
            into.fill(0x0101)
            return into.size
        }
        override fun close() {
            open = false
            closes.incrementAndGet()
        }
    }

    @Before
    fun startSession() {
        AaLog.sink = { log += it }
        ConscryptInitializer.initialize()
        peer = FakeAndroidAutoPeer()
        connection = SocketAccessoryConnection(Socket(peer.address, peer.port))
        transport = AapTransport(context)
        assertTrue("handshake failed: $log", transport.startHandshake(connection))
        assertTrue(peer.awaitHandshake())
        microphone = AaMicrophone(voice, transport::send, log = { log += it })
        transport.startReading(AapMessageHandlerType(transport, AaConfig(), { false }, null, microphone))
    }

    @After
    fun tearDown() {
        microphone.stop("test end")
        transport.quit()
        connection.disconnect()
        peer.close()
        AaLog.sink = null
    }

    private fun send(channel: Int, type: Int, body: Message) = peer.send(channel, type, body.toByteArray(), 0x0B)

    private fun next(what: String): FakeAndroidAutoPeer.Received = checkNotNull(peer.receive()) { "$what: nothing came (log: $log)" }

    @Test
    fun withAVoiceTheMicrophoneOpensAndTheVoiceGoesToAndroidAutoUntilItCloses() {
        send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(5).setConfigurationIndex(0).build())
        send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE, Media.MicrophoneRequest.newBuilder().setOpen(true).build())

        val answer = next("microphone response")
        assertEquals(Channel.ID_MIC, answer.channel)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE, answer.type)
        val response = Media.MicrophoneResponse.parseFrom(answer.body)
        assertEquals("it opened", Common.MessageStatus.STATUS_SUCCESS_VALUE, response.status)
        assertEquals("with the session Android Auto started on this channel", 5, response.sessionId)

        val voiceData = (1..3).map { next("voice $it") }
        voiceData.forEach {
            assertEquals(Channel.ID_MIC, it.channel)
            assertEquals("media data", Media.MsgType.MEDIA_MESSAGE_DATA_VALUE, it.type)
            assertEquals("8 bytes of time and 20 ms of 16 kHz mono", 8 + AaMicrophone.CHUNK_SAMPLES * 2, it.body.size)
            assertEquals("the samples as the voice gave them", 0x01.toByte(), it.body[8])
        }

        send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE, Media.MicrophoneRequest.newBuilder().setOpen(false).build())
        val deadline = System.currentTimeMillis() + 2_000
        while (closes.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("the voice was closed once", 1, closes.get())
        assertEquals(1, opens.get())

        // Once it is closed, the close's answer comes (after any chunk already on the way) and then nothing more.
        var closeAnswer: FakeAndroidAutoPeer.Received
        do closeAnswer = next("close response") while (closeAnswer.type == Media.MsgType.MEDIA_MESSAGE_DATA_VALUE)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE, closeAnswer.type)
        assertEquals(Common.MessageStatus.STATUS_SUCCESS_VALUE, Media.MicrophoneResponse.parseFrom(closeAnswer.body).status)
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build())
        assertEquals("no voice after the close, only the ping's answer", Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, next("ping response").type)
    }
}
