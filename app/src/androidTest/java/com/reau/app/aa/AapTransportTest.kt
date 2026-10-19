package com.reau.app.aa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.R
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.security.cert.CertificateFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The copied transport (AapTransport: the version + TLS handshake, the send thread, the read loop) against the
 * fake Android Auto, on the emulator, with the real head-unit certificate from res/raw and the bundled Conscrypt.
 * Characterisation, written AFTER the code; the last-attempt wait is the open-headunit fix.
 */
@RunWith(AndroidJUnit4::class)
class AapTransportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val quits = CopyOnWriteArrayList<Boolean>()
    private val handled = CopyOnWriteArrayList<AapMessage>()
    private var peer: FakeAndroidAutoPeer? = null
    private var connection: SocketAccessoryConnection? = null
    private var transport: AapTransport? = null

    private val handler = object : AapMessageHandler {
        override fun handle(message: AapMessage) {
            handled += message
        }
    }

    @Before
    fun setUp() {
        AaLog.sink = { log += it }
        ConscryptInitializer.initialize()
    }

    @After
    fun tearDown() {
        transport?.quit()
        connection?.disconnect()
        peer?.close()
        AaLog.sink = null
    }

    /** A transport connected to a fake peer that follows [script]; onQuit is recorded. */
    private fun connect(
        script: FakeAndroidAutoPeer.Script = FakeAndroidAutoPeer.Script(),
        minor: Int = Messages.MINOR_DEFAULT,
    ): Triple<FakeAndroidAutoPeer, SocketAccessoryConnection, AapTransport> {
        val fake = FakeAndroidAutoPeer(script).also { peer = it }
        val conn = SocketAccessoryConnection(Socket(fake.address, fake.port)).also { connection = it }
        val t = AapTransport(context, protocolMinor = minor).also { transport = it }
        t.onQuit = { clean -> quits += clean }
        return Triple(fake, conn, t)
    }

    private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (log: $log)", condition())
    }

    // ── the handshake ──────────────────────────────────────────────────────────────────────────────────────

    /** With the test switch, 1.7 is asked; the version Android Auto answers is in the log. */
    @Test
    fun theNewerProtocolTestAsksForOnePointSevenAndLogsTheAnswer() {
        val (fake, conn, t) = connect(FakeAndroidAutoPeer.Script(minorVersion = 7), minor = Messages.MINOR_NEWER)

        assertTrue("handshake failed: $log", t.startHandshake(conn))
        assertTrue("the peer never saw it finish: ${fake.events}", fake.awaitHandshake())

        assertArrayEquals("the version request", byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 7), fake.versionRequest)
        assertTrue("the answer in the log: $log", log.any { "Version response received: 1.7, status 0" in it })
    }

    @Test
    fun theHandshakeGoesThroughVersionTlsAndStatusOk() {
        val (fake, conn, t) = connect()

        assertTrue("handshake failed: $log", t.startHandshake(conn))
        assertTrue("the peer never saw it finish: ${fake.events}", fake.awaitHandshake())

        assertArrayEquals("the version request", byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 2), fake.versionRequest)
        assertArrayEquals("the status OK", byteArrayOf(0, 3, 0, 4, 0, 4, 8, 0), fake.statusOk)
        // On Conscrypt the head unit sends one empty TLS message after its handshake is over, before the status OK.
        assertEquals(
            listOf("accepted", "version request", "version answered", "tls done", "empty tls message", "status ok"),
            fake.events.toList(),
        )
        assertTrue(t.isAlive)
        assertTrue("got $log", log.any { "Status OK sent" in it && "Handshake successful" in it })
        assertTrue("nothing quit", quits.isEmpty())
    }

    @Test
    fun theHeadUnitPresentsTheCertificateThatShipsWithTheApp() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())

        val shipped = context.resources.openRawResource(R.raw.aa_cert).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        assertEquals(shipped, fake.clientCertificate)
        assertEquals("TLSv1.2", fake.protocol)
    }

    @Test
    fun lateVersionResponsesDoNotBreakTheHandshake() {
        val (fake, conn, t) = connect(FakeAndroidAutoPeer.Script(lateVersionResponses = 2))
        assertTrue("handshake failed: $log", t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        assertEquals(2, log.count { "discarded a late VERSION_RESPONSE" in it })
    }

    // ── messages after the handshake ───────────────────────────────────────────────────────────────────────

    @Test
    fun aMessageSentByTheTransportReachesThePeerDecrypted() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())

        t.send(VideoFocusEvent(gain = true, unsolicited = false))
        t.setSessionId(Channel.ID_VID, 7)
        t.sendMediaAck(Channel.ID_VID)

        val focus = checkNotNull(fake.receive()) { "nothing arrived: ${fake.events}" }
        assertEquals(Channel.ID_VID, focus.channel)
        assertEquals(0x0B, focus.flags)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, focus.type)
        assertArrayEquals(byteArrayOf(0x08, 1, 0x10, 0), focus.body)
        val ack = checkNotNull(fake.receive())
        assertEquals(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack.type)
        assertArrayEquals("the ack carries the session id set for that channel", byteArrayOf(0x08, 7, 0x10, 1), ack.body)
    }

    @Test
    fun messagesFromThePeerReachTheHandlerOnceReadingStarts() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        fake.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(42).build().toByteArray())
        Thread.sleep(200)
        assertTrue("nothing is read before startReading()", handled.isEmpty())

        t.startReading(handler)
        fake.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(3).setConfigurationIndex(0).build().toByteArray())
        await("two messages must reach the handler, got ${handled.size}") { handled.size == 2 }

        assertEquals(Channel.ID_CTR, handled[0].channel)
        assertEquals(Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, handled[0].type)
        assertEquals(42L, handled[0].parse(Control.PingRequest.newBuilder()).timestamp)
        assertEquals(Channel.ID_VID, handled[1].channel)
        assertEquals(3, handled[1].parse(Media.Start.newBuilder()).sessionId)
    }

    @Test
    fun theNightSensorIsOnlyReportedOnceAndroidAutoStartedIt() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())

        t.sendNightMode(true)
        assertEquals("not started yet: nothing goes out", null, fake.receive(300))
        assertTrue("but the value is remembered", t.nightMode)

        t.startSensor(10) // Sensors.SensorType.NIGHT
        t.sendNightMode(false)
        val night = checkNotNull(fake.receive())
        assertEquals(Channel.ID_SEN, night.channel)
        assertArrayEquals("night_mode (field 10) { is_night_mode = false }", byteArrayOf(0x52, 2, 0x08, 0), night.body)
    }

    @Test
    fun aKeyframeIsAskedForByTakingVideoFocusAwayAndGivingItBack() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())

        t.triggerFocusCycleRecovery()

        val away = checkNotNull(fake.receive())
        assertArrayEquals("native mode, not unsolicited", byteArrayOf(0x08, 2, 0x10, 0), away.body)
        val back = checkNotNull(fake.receive())
        assertArrayEquals("projected mode, unsolicited", byteArrayOf(0x08, 1, 0x10, 1), back.body)
    }

    // ── endings ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun stoppingSaysByeByeAndQuits() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())

        t.stop()

        val bye = checkNotNull(fake.receive()) { "no byebye arrived" }
        assertEquals(Channel.ID_CTR, bye.channel)
        assertEquals(Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, bye.type)
        assertEquals(Control.ByeByeReason.USER_SELECTION, Control.ByeByeRequest.parseFrom(bye.body).reason)
        assertEquals("quit is reported once, as not clean", listOf(false), quits.toList())
        assertFalse(t.isAlive)
    }

    // ── Android Auto crashed in its head unit server's thread
    // (NullPointerException on ServerSocket.isClosed) about a second after REau ended a session. REau used to close the
    // connection a blind 150 ms after its bye; now it waits for Android Auto's answer (up to BYE_WAIT_MS). ──

    @Test
    fun stoppingWaitsForAndroidAutosAnswerToTheByeBeforeClosing() {
        val (fake, conn, t) = connect(FakeAndroidAutoPeer.Script(answerByeAfterMs = 400))
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        t.startReading(handler)

        t.stop()

        assertTrue("the answer got through before the socket closed (events: ${fake.events})", "bye answered" in fake.events)
        assertTrue("the log says the bye was answered (log: $log)", log.any { "bye answered" in it })
        assertEquals("an answered bye is a clean end", listOf(true), quits.toList())
        assertFalse(t.isAlive)
    }

    @Test
    fun anAndroidAutoThatDoesNotAnswerTheByeIsLeftAfterTheWait() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        t.startReading(handler)

        val began = System.currentTimeMillis()
        t.stop()
        val took = System.currentTimeMillis() - began

        assertTrue("stop took $took ms", took in AapTransport.BYE_WAIT_MS..AapTransport.BYE_WAIT_MS + 1_500)
        assertTrue("the log says nobody answered (log: $log)", log.any { "no answer to the bye" in it })
        assertEquals("an unanswered bye is not a clean end", listOf(false), quits.toList())
        assertFalse(t.isAlive)
    }

    @Test
    fun thePeerHangingUpWhileReadingEndsTheSessionWithTheReasonInTheLog() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        t.startReading(handler)

        fake.hangUp()

        await("the transport must quit when the peer hangs up") { quits.isNotEmpty() }
        assertEquals(listOf(false), quits.toList())
        assertTrue("got $log", log.any { "AapRead: Connection closed (EOF)" in it })
        await("the threads must end") { !t.isAlive }
    }

    @Test
    fun thePeerLeavingOnPurposeIsACleanEnd() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        t.startReading(handler)

        fake.sendRaw(ByteArray(4) { 0xFF.toByte() })

        await("the transport must quit") { quits.isNotEmpty() }
        assertEquals("clean", listOf(true), quits.toList())
        assertTrue("got $log", log.any { "Magic Garbage detected in header" in it })
    }

    // ── keeping a still Android Auto talking ───────────────────────────────────────────────────────────────────
    // The "freeze": with its screen still (the app list after "home" from the joystick) Android Auto
    // sent nothing, and after 15 s of silence the read gave up and ended the session. REau now
    // pings every few seconds, and the answer keeps the read alive.

    @Test
    fun anAndroidAutoThatOnlyAnswersPingsKeepsTheSessionPastTheReadTimeout() {
        val (fake, conn, t) = connect(FakeAndroidAutoPeer.Script(answerPings = true))
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        t.startReading(handler)

        Thread.sleep(20_000)

        assertTrue("the session must still be up after 20 s (quits $quits, log: $log)", quits.isEmpty() && t.isAlive)
        assertTrue("no read timeout: $log", log.none { "read timeout" in it })
        val pings = fake.pingsReceived.get()
        assertTrue("pings sent in 20 s: $pings", pings >= 3)
    }

    @Test
    fun anAndroidAutoThatSaysNothingAtAllStillEndsTheSessionAfterTheReadTimeout() {
        val (fake, conn, t) = connect()
        assertTrue(t.startHandshake(conn))
        assertTrue(fake.awaitHandshake())
        t.startReading(handler)

        await("a peer that answers nothing, not even pings, must end the session", timeoutMs = 25_000) { quits.isNotEmpty() }
        assertTrue("got $log", log.any { "WiFi read timeout" in it })
    }

    // ── the peer going away during the handshake ───────────────────────────────────────────────────────────

    @Test
    fun aPeerThatHangsUpBeforeAnsweringTheVersionFailsTheHandshake() {
        val (_, conn, t) = connect(FakeAndroidAutoPeer.Script(hangUpAt = FakeAndroidAutoPeer.Stage.ACCEPTED))

        assertFalse(t.startHandshake(conn))

        assertTrue("got $log", log.any { "Version request/response failed" in it })
        assertTrue("got $log", log.any { "Handshake failed" in it })
        assertEquals("the failed handshake quits the transport", listOf(false), quits.toList())
        assertFalse(t.isAlive)
    }

    @Test
    fun aPeerThatHangsUpInTheMiddleOfTlsFailsTheHandshake() {
        val (_, conn, t) = connect(FakeAndroidAutoPeer.Script(hangUpAt = FakeAndroidAutoPeer.Stage.TLS_HALFWAY))

        assertFalse(t.startHandshake(conn))

        assertTrue("got $log", log.any { "SSL Handshake: Failed to read AAP" in it })
        assertTrue("got $log", log.any { "SSL performHandshake failed" in it })
        assertEquals(listOf(false), quits.toList())
        assertFalse(t.isAlive)
    }

    @Test
    fun aPeerThatNeverAnswersIsGivenUpOnAfterTheHandshakeBudget() {
        val (_, conn, t) = connect(FakeAndroidAutoPeer.Script(silent = true))
        val startedAt = System.currentTimeMillis()

        assertFalse(t.startHandshake(conn))

        val tookMs = System.currentTimeMillis() - startedAt
        // Two attempts of 2 s, then the last one waits out what is left of the 10 s budget (the open-headunit fix).
        assertTrue("gave up after $tookMs ms", tookMs in 9_000..13_000)
        assertEquals(3, log.count { "No VERSION_RESPONSE within" in it })
        assertTrue("got $log", log.any { "No VERSION_RESPONSE within the handshake budget (attempt 3)" in it })
        assertEquals(listOf(false), quits.toList())
    }
}
