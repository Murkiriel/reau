package com.reau.app.aa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.Message
import com.reau.app.aa.proto.Common
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Input
import com.reau.app.aa.proto.Media
import com.reau.app.aa.proto.Sensors
import com.reau.app.video.AaMargins
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The copied control layer (AapControl, AapMessageHandlerType) with the new service discovery response, in a whole
 * session against the fake Android Auto: the fake asks, the receiver answers. Characterisation of copied code,
 * written AFTER it, except the microphone answer and the configuration reaching the discovery response, which are
 * this app's changes.
 */
@RunWith(AndroidJUnit4::class)
class AapControlTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val quits = CopyOnWriteArrayList<Boolean>()
    private val videoSeen = CopyOnWriteArrayList<AapMessage>()
    private lateinit var peer: FakeAndroidAutoPeer
    private lateinit var connection: SocketAccessoryConnection
    private lateinit var transport: AapTransport
    private val config = AaConfig(density = 200, margins = AaMargins(26, 0))

    @Before
    fun startSession() {
        AaLog.sink = { log += it }
        ConscryptInitializer.initialize()
        peer = FakeAndroidAutoPeer()
        connection = SocketAccessoryConnection(Socket(peer.address, peer.port))
        transport = AapTransport(context)
        transport.onQuit = { clean -> quits += clean }
        assertTrue("handshake failed: $log", transport.startHandshake(connection))
        assertTrue(peer.awaitHandshake())
        // The video path of this test: takes media data on the video channel (types 0 and 1), nothing else.
        transport.startReading(
            AapMessageHandlerType(transport, config) { message ->
                (message.type == 0 || message.type == 1).also { if (it) videoSeen += message }
            },
        )
    }

    @After
    fun tearDown() {
        transport.quit()
        connection.disconnect()
        peer.close()
        AaLog.sink = null
    }

    private fun send(channel: Int, type: Int, body: Message, flags: Int = 0x0B) = peer.send(channel, type, body.toByteArray(), flags)

    private fun next(what: String): FakeAndroidAutoPeer.Received = checkNotNull(peer.receive()) { "$what: nothing came (log: $log)" }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (log: $log)", condition())
    }

    /** A ping is answered: proof that the session is alive and the previous message was dealt with. */
    private fun assertStillAlive() {
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build())
        assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, next("ping response").type)
    }

    // ── discovery and channels ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aDiscoveryRequestIsAnsweredWithTheServicesOfTheConfiguration() {
        send(
            Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
            Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build(),
        )

        val answer = next("discovery response")
        assertEquals(Channel.ID_CTR, answer.channel)
        assertEquals(Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, answer.type)
        val response = Control.ServiceDiscoveryResponse.parseFrom(answer.body)
        assertEquals(listOf(Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU2, Channel.ID_MIC), response.servicesList.map { it.id })
        val video = response.servicesList.single { it.id == Channel.ID_VID }.mediaSinkService.videoConfigsList.single()
        assertEquals("the configuration of this session, not a default", 200, video.density)
        assertEquals(26, video.marginWidth)
        assertTrue("got $log", log.any { "Service Discovery Request: Fake phone" in it })
    }

    @Test
    fun everyAdvertisedChannelOpensAndTheSensorChannelReportsUnrestrictedDriving() {
        for (channel in listOf(Channel.ID_VID, Channel.ID_INP, Channel.ID_AU2, Channel.ID_MIC)) {
            send(channel, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE, Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(channel).build(), flags = 0x0F)
            val opened = next("channel open response on $channel")
            assertEquals(channel, opened.channel)
            assertEquals("a control-type message outside the control channel", 0x0F, opened.flags)
            assertEquals(Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, opened.type)
            assertEquals(Common.MessageStatus.STATUS_SUCCESS, Control.ChannelOpenResponse.parseFrom(opened.body).status)
        }

        send(Channel.ID_SEN, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE, Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(Channel.ID_SEN).build(), flags = 0x0F)
        assertEquals(Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, next("sensor channel open").type)
        val driving = next("driving status")
        assertEquals(Channel.ID_SEN, driving.channel)
        assertEquals(Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, driving.type)
        assertArrayEquals("driving_status { status = UNRESTRICTED }", byteArrayOf(0x6A, 2, 0x08, 0), driving.body)
    }

    // ── sensors ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun startingTheNightSensorIsAcknowledgedAndAnsweredWithTheCurrentValue() {
        transport.sendNightMode(true) // remembered; the sensor is not started yet
        send(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE, Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.NIGHT).setMinUpdatePeriod(0).build())

        val started = next("sensor start response")
        assertEquals(Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, started.type)
        assertEquals(Common.MessageStatus.STATUS_SUCCESS, Sensors.SensorResponse.parseFrom(started.body).status)
        assertArrayEquals("night_mode { is_night_mode = true }", byteArrayOf(0x52, 2, 0x08, 1), next("night value").body)

        transport.sendNightMode(false) // from now on changes go out
        assertArrayEquals(byteArrayOf(0x52, 2, 0x08, 0), next("night change").body)
    }

    @Test
    fun startingTheDrivingStatusSensorIsAcknowledgedAndSendsNoNightValue() {
        send(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE, Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.DRIVING_STATUS).setMinUpdatePeriod(0).build())
        assertEquals(Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, next("sensor start response").type)
        assertNull("nothing else for this sensor", peer.receive(300))
    }

    // ── video and audio sinks ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun settingUpTheVideoSinkIsAnsweredWithTheWindowOf12AndVideoFocus() {
        send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, Media.MediaSetupRequest.newBuilder().setType(3).build())

        val setup = next("config response")
        assertEquals(Channel.ID_VID, setup.channel)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, setup.type)
        val media = Media.Config.parseFrom(setup.body)
        assertEquals(Media.Config.ConfigStatus.HEADUNIT, media.status)
        assertEquals("Android Auto stops sending video at 12 unacknowledged messages", 12, media.maxUnacked)
        assertEquals(listOf(0), media.configurationIndicesList)

        val focus = next("video focus")
        assertEquals(Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, focus.type)
        assertArrayEquals("projected, unsolicited", byteArrayOf(0x08, 1, 0x10, 1), focus.body)
    }

    @Test
    fun settingUpAnAudioSinkGetsAWindowOf16AndNoVideoFocus() {
        send(Channel.ID_AU2, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, Media.MediaSetupRequest.newBuilder().setType(1).build())
        val setup = next("config response")
        assertEquals(Channel.ID_AU2, setup.channel)
        assertEquals(16, Media.Config.parseFrom(setup.body).maxUnacked)
        assertNull(peer.receive(300))
    }

    @Test
    fun videoDataGoesToTheVideoPathAndIsAcknowledgedWithTheSessionOfItsStart() {
        send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(9).setConfigurationIndex(0).build())
        peer.send(Channel.ID_VID, type = 1, body = byteArrayOf(1, 2, 3)) // codec configuration
        peer.send(Channel.ID_VID, type = 0, body = byteArrayOf(4, 5, 6)) // media data

        for (expected in listOf("first ack", "second ack")) {
            val ack = next(expected)
            assertEquals(Channel.ID_VID, ack.channel)
            assertEquals(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack.type)
            assertArrayEquals("session_id = 9, ack = 1", byteArrayOf(0x08, 9, 0x10, 1), ack.body)
        }
        assertEquals(listOf(1, 0), videoSeen.map { it.type })
        assertNull("one ack per message", peer.receive(300))
    }

    @Test
    fun soundOnTheSystemAudioChannelIsAcknowledgedAndThrownAway() {
        send(Channel.ID_AU2, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(4).setConfigurationIndex(0).build())
        peer.send(Channel.ID_AU2, type = 0, body = ByteArray(320))

        val ack = next("audio ack")
        assertEquals(Channel.ID_AU2, ack.channel)
        assertArrayEquals(byteArrayOf(0x08, 4, 0x10, 1), ack.body)
        assertTrue("audio never reaches the video path", videoSeen.isEmpty())
    }

    @Test
    fun aVideoStopRequestIsNotAnsweredAndClearsTheForcedKeyframeMark() {
        transport.ignoreNextStopRequest = true
        peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_STOP_VALUE, ByteArray(0))
        assertStillAlive()
        assertFalse(transport.ignoreNextStopRequest)
        assertTrue("got $log", log.any { "Video Sink Stopped -> Ignored" in it })
    }

    // ── video focus ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun androidAutoAskingForProjectedFocusGetsIt() {
        send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE, Media.VideoFocusRequestNotification.newBuilder().setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED).build())
        val focus = next("video focus")
        assertEquals(Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, focus.type)
        assertArrayEquals("projected, as an answer", byteArrayOf(0x08, 1, 0x10, 0), focus.body)
    }

    @Test
    fun androidAutoAskingForNativeFocusIsTheUserLeavingAndEndsTheSession() {
        send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE, Media.VideoFocusRequestNotification.newBuilder().setMode(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE).build())

        val bye = next("byebye")
        assertEquals(Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, bye.type)
        await("the transport must quit") { quits.isNotEmpty() }
        assertTrue(transport.wasUserExit)
        assertEquals(listOf(false), quits.toList())
    }

    // ── the control channel ────────────────────────────────────────────────────────────────────────────────

    /** The answer to REau's own keep-alive ping is taken quietly (it used to be "Unsupported" every 5 s). */
    @Test
    fun theAnswerToOurOwnPingIsTakenQuietly() {
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, Control.PingResponse.newBuilder().setTimestamp(7).build())
        assertStillAlive()
        assertTrue("got $log", log.none { "Unsupported Control message" in it })
    }

    @Test
    fun aPingIsAnsweredWithATimestamp() {
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(42).build())
        val pong = next("ping response")
        assertEquals(Channel.ID_CTR, pong.channel)
        assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, pong.type)
        assertTrue(Control.PingResponse.parseFrom(pong.body).timestamp > 0)
    }

    @Test
    fun aNavigationFocusRequestIsGranted() {
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE, Control.NavFocusRequestNotification.newBuilder().setFocusType(Control.NavFocusType.NAV_FOCUS_2).build())
        val focus = next("navigation focus")
        assertEquals(Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE, focus.type)
        assertEquals(Control.NavFocusType.NAV_FOCUS_2, Control.NavFocusNotification.parseFrom(focus.body).focusType)
    }

    @Test
    fun everyAudioFocusRequestIsGrantedInKind() {
        val request = Control.AudioFocusRequestNotification.AudioFocusRequestType::class
        val state = Control.AudioFocusNotification.AudioFocusStateType::class
        val expected = listOf(
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK to
                Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE to Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS,
        )
        for ((asked, granted) in expected) {
            send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE, Control.AudioFocusRequestNotification.newBuilder().setRequest(asked).build())
            val answer = next("audio focus for $asked")
            assertEquals(Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, answer.type)
            assertEquals("${request.simpleName} $asked -> ${state.simpleName}", granted, Control.AudioFocusNotification.parseFrom(answer.body).focusState)
        }
    }

    @Test
    fun aByeByeFromAndroidAutoIsAnsweredAndEndsTheSessionCleanly() {
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, Control.ByeByeRequest.newBuilder().setReason(Control.ByeByeReason.USER_SELECTION).build())

        assertEquals(Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE, next("byebye response").type)
        await("the transport must quit") { quits.isNotEmpty() }
        assertEquals("clean", listOf(true), quits.toList())
        await("the threads must end") { !transport.isAlive }
    }

    @Test
    fun noticesThatNeedNoAnswerGetNone() {
        send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE, Control.VoiceSessionNotification.newBuilder().setStatus(Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_START).build())
        peer.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE, ByteArray(0))
        assertStillAlive()
        assertNull("only the ping was answered", peer.receive(300))
        assertTrue("got $log", log.any { "Voice Session Notification: START" in it })
    }

    @Test
    fun messagesTheReceiverDoesNotKnowAreLoggedAndTheSessionGoesOn() {
        peer.send(Channel.ID_CTR, type = 25, body = ByteArray(0)) // a control type with no handler
        peer.send(Channel.ID_VID, type = 40_000, body = ByteArray(0)) // a type outside every known range
        peer.send(Channel.ID_INP, type = 32_780, body = ByteArray(0)) // an input type with no handler
        assertStillAlive()
        assertTrue("got $log", log.any { "Unsupported Control message type: 25" in it })
        assertTrue("got $log", log.any { "Unknown msg_type: 40000" in it })
        assertTrue("got $log", log.any { "Unsupported Input message type: 32780" in it })
    }

    // ── input and microphone ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aKeyBindingRequestIsAccepted() {
        send(Channel.ID_INP, Input.MsgType.BINDINGREQUEST_VALUE, Input.KeyBindingRequest.newBuilder().addKeycodes(19).addKeycodes(65536).build())
        val bound = next("binding response")
        assertEquals(Channel.ID_INP, bound.channel)
        assertEquals(Input.MsgType.BINDINGRESPONSE_VALUE, bound.type)
        assertEquals(Common.MessageStatus.STATUS_SUCCESS, Input.BindingResponse.parseFrom(bound.body).status)
    }

    @Test
    fun theMicrophoneIsNeverOpenedAndSaysSo() {
        send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(5).setConfigurationIndex(0).build())
        send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE, Media.MicrophoneRequest.newBuilder().setOpen(true).build())

        val refused = next("microphone response")
        assertEquals(Channel.ID_MIC, refused.channel)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE, refused.type)
        val answer = Media.MicrophoneResponse.parseFrom(refused.body)
        assertEquals("it did not open", Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE, answer.status)
        assertEquals("with the session Android Auto started on this channel", 5, answer.sessionId)

        send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE, Media.MicrophoneRequest.newBuilder().setOpen(false).build())
        assertEquals("closing what was never open is fine", Common.MessageStatus.STATUS_SUCCESS_VALUE, Media.MicrophoneResponse.parseFrom(next("close response").body).status)
        assertStillAlive()
    }
}
