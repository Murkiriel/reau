package com.reau.app.aa

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import com.reau.app.video.H264TestDecoder
import com.reau.app.video.PictureCheck
import com.reau.app.video.SoftwareH264Decoder
import com.reau.app.video.TestH264Source
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** The rider's Desligar: what "quick" means for a receiver whose bye was answered at once. */
private const val QUICK_STOP_MS = 500L
private const val LONG_AFTER_DROP_MS = 3_000L
private const val SWALLOW_MS = 300L

/**
 * The link to Android Auto: the receiver dials the head unit server Android Auto opens on 127.0.0.1:5277, keeps one
 * session at a time and says where the link stands. The Android Auto here is the fake one, on a port of the test's
 * choosing (and once on 5277 itself, which is free on an emulator: there is no Android Auto in it).
 */
@RunWith(AndroidJUnit4::class)
class AaReceiverTest {
    /** The pictures are read back through an ImageReader: from the software decoder, which every device can read (see the rule). */
    @get:org.junit.Rule val softwareDecoder = SoftwareH264Decoder()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<AaLinkState>()
    private val config = AaConfig(density = 180)
    private val readerThread = HandlerThread("test-image-reader").apply { start() }
    private val reader = ImageReader.newInstance(TestPattern.WIDTH, TestPattern.HEIGHT, ImageFormat.YUV_420_888, 4)
    private val lastPicture = AtomicReference<H264TestDecoder.Picture?>()
    private val peers = mutableListOf<FakeAndroidAutoPeer>()
    private var receiver: AaReceiver? = null

    @Before
    fun setUp() {
        AaLog.sink = { log += it }
        // The time of the last stop is the process's, and the tests before this one stopped receivers: without a stop
        // to count from, the receiver says what it always said (the test of the state right after a stop sets its own).
        AaRestartGap.reset(context)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { lastPicture.set(H264TestDecoder.pictureOf(it)) }
        }, Handler(readerThread.looper))
    }

    @After
    fun tearDown() {
        receiver?.stop()
        peers.forEach { it.close() }
        reader.close()
        readerThread.quitSafely()
        AaLog.sink = null
    }

    private val quick = AaReceiver.Timing(redialMs = 200, afterDropMs = 100)

    /** Slow to dial again after a drop: the test has time to change what is listening. */
    private val patient = AaReceiver.Timing(redialMs = 200, afterDropMs = 1_500)

    private fun receiverOn(port: Int, timing: AaReceiver.Timing = quick, config: AaConfig = this.config) =
        AaReceiver(context, config, reader.surface, { states += it }, AaReceiver.Options(port, timing)).also { receiver = it }

    private fun peerOn(port: Int, script: FakeAndroidAutoPeer.Script = FakeAndroidAutoPeer.Script()) =
        FakeAndroidAutoPeer(script, listenOn = port).also { peers += it }

    /** A port nothing listens on. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitState(expected: AaLinkState, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (states.lastOrNull() != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("states so far: $states (log: $log)", expected, states.lastOrNull())
    }

    private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (states: $states, log: $log)", condition())
    }

    private fun next(peer: FakeAndroidAutoPeer, what: String): FakeAndroidAutoPeer.Received {
        val received = peer.receive()
        assertNotNull("$what: nothing came (states: $states, log: $log)", received)
        return received!!
    }

    // ── Android Auto accepting and never answering is said, not hidden ─────────────────────────────────────

    /** Accepts every connection and says nothing, holding it open: Android Auto's server stuck. */
    private class MuteServer : java.io.Closeable {
        private val server = ServerSocket(0)
        private val held = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val port get() = server.localPort
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        private val worker = kotlin.concurrent.thread(name = "mute-android-auto") {
            runCatching { while (true) { held += server.accept(); accepted.incrementAndGet() } }
        }

        override fun close() {
            server.close()
            held.forEach { runCatching { it.close() } }
            worker.join(1_000)
        }
    }

    @Test
    fun threeHandshakesWithNoAnswerAreTheStateUnresponsive() {
        MuteServer().use { mute ->
            // The doubt of a session REau did not see end (begun by the receiver itself) is already past its window.
            AaRestartGap.maybeClosingSinceMs = android.os.SystemClock.elapsedRealtime() - PreviousSessionClosing.WINDOW_MS - 1
            receiverOn(mute.port).start()
            awaitState(AaLinkState.UNRESPONSIVE, timeoutMs = 45_000)
            assertTrue("three tries before saying so: ${mute.accepted.get()}", mute.accepted.get() >= 3)
            assertEquals("said once: $log", 1, log.count { "Android Auto accepts the connection and does not answer" in it })
        }
    }

    @Test
    fun theNoticeOfUnresponsiveStaysWhileTheNextRoundsAreAcceptedAndLeftUnanswered() {
        // Escrito, não rodado: o estado voltava a "Conectando" a cada discagem aceita e a "Android Auto não responde" a cada falha.
        MuteServer().use { mute ->
            AaRestartGap.maybeClosingSinceMs = android.os.SystemClock.elapsedRealtime() - PreviousSessionClosing.WINDOW_MS - 1
            receiverOn(mute.port).start()
            awaitState(AaLinkState.UNRESPONSIVE, timeoutMs = 45_000)
            val acceptedWhenSaid = mute.accepted.get()
            val sayingIndex = states.indexOf(AaLinkState.UNRESPONSIVE)

            await("two more rounds are accepted and left unanswered", timeoutMs = 60_000) { mute.accepted.get() >= acceptedWhenSaid + 2 }

            assertEquals("nothing else was reported after it: $states", sayingIndex + 1, states.size)
            assertEquals(AaLinkState.UNRESPONSIVE, states.last())
        }
    }

    @Test
    fun threeHandshakesWithNoAnswerFromTheFirstDialOfAReceiverWithNoStopAreAPreviousSessionTheOtherEditionEnded() {
        // Escrito, não rodado: 18/10, a edição completa ligada logo depois do Desligar da Lite. Sem parada própria para contar,
        // o receptor conta a dúvida a partir do início das discagens (AaRestartGap.maybeClosingSinceMs).
        MuteServer().use { mute ->
            receiverOn(mute.port).start()
            awaitState(AaLinkState.CLOSING_PREVIOUS, timeoutMs = 45_000)
            assertTrue("three tries before saying so: ${mute.accepted.get()}", mute.accepted.get() >= 3)
            assertTrue("the log says it did not see the session end: $log", log.any { "this REau did not see end" in it })
        }
    }

    @Test
    fun threeHandshakesWithNoAnswerRightAfterAStopAreTheServerClosingThePreviousSession() {
        MuteServer().use { mute ->
            AaRestartGap.lastStoppedAtMs = android.os.SystemClock.elapsedRealtime()
            receiverOn(mute.port).start()
            awaitState(AaLinkState.CLOSING_PREVIOUS, timeoutMs = 45_000)
            assertTrue("three tries before saying so: ${mute.accepted.get()}", mute.accepted.get() >= 3)
            assertTrue("the log says it clears by itself: $log", log.any { "still closing the previous session" in it })
        }
    }

    @Test
    fun theClosingPreviousSessionNoticeStaysWhileTheNextRoundsAreAcceptedAndLeftUnanswered() {
        // Escrito, não rodado: na sessão real de 18/10 o aviso piscava entre "Fechando a sessão anterior" e "Conectando"
        // a cada rodada de discagens aceitas e mudas (Android Auto 17.8, servidor ainda escutando ~80 s após o Desligar).
        MuteServer().use { mute ->
            AaRestartGap.lastStoppedAtMs = android.os.SystemClock.elapsedRealtime()
            receiverOn(mute.port).start()
            awaitState(AaLinkState.CLOSING_PREVIOUS, timeoutMs = 45_000)
            val acceptedWhenSaid = mute.accepted.get()
            val sayingIndex = states.indexOf(AaLinkState.CLOSING_PREVIOUS)

            await("two more rounds are accepted and left unanswered", timeoutMs = 60_000) { mute.accepted.get() >= acceptedWhenSaid + 2 }

            assertEquals("nothing else was reported after it: $states", sayingIndex + 1, states.size)
            assertEquals(AaLinkState.CLOSING_PREVIOUS, states.last())
        }
    }

    // ── a start right after a stop waits a second before dialling ──────────────────────────────────────────

    @Test
    fun aStartRightAfterAStopDialsOnlyASecondAfterTheStop() {
        val first = receiverOn(freePort())
        first.start()
        awaitState(AaLinkState.SERVER_OFF)
        val port = freePort()
        val peer = peerOn(port)
        first.stop()
        val stoppedAt = System.currentTimeMillis()
        receiverOn(port).start()
        await("the second receiver dials", timeoutMs = 5_000) { "accepted" in peer.events }
        val waited = System.currentTimeMillis() - stoppedAt
        assertTrue("dialled $waited ms after the stop (log: $log)", waited >= 900)
        assertTrue("got $log", log.any { "after the stop before dialling" in it })
    }

    // ── the server is not there ────────────────────────────────────────────────────────────────────────────

    @Test
    fun withNothingListeningTheStateIsServerOffAndStaysSoWhileTheReceiverKeepsDialling() {
        val r = receiverOn(freePort())
        assertEquals("before it is started", AaLinkState.IDLE, r.state)

        r.start()
        awaitState(AaLinkState.SERVER_OFF)
        Thread.sleep(1_000) // five more dials at 200 ms

        assertEquals("one report per change, not one per dial", listOf(AaLinkState.CONNECTING, AaLinkState.SERVER_OFF), states.toList())
        assertEquals(AaLinkState.SERVER_OFF, r.state)
        assertEquals("and one line in the log, not one per dial: $log", 1, log.count { "no answer from the head unit server" in it })
    }

    /** The test switch reaches the session; without it, 1.2 as always. */
    @Test
    fun theSessionAsksForTheProtocolTheSettingsSay() {
        val port = freePort()
        val peer = peerOn(port)
        receiverOn(port, config = config.copy(protocolMinor = Messages.MINOR_NEWER)).start()
        assertTrue(peer.awaitHandshake())
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 7), peer.versionRequest)
    }

    @Test
    fun theServerComingUpLaterIsFoundByTheNextDial() {
        val port = freePort()
        receiverOn(port).start()
        awaitState(AaLinkState.SERVER_OFF)

        val peer = peerOn(port)
        awaitState(AaLinkState.ACTIVE)

        assertTrue(peer.awaitHandshake())
        assertEquals(listOf(AaLinkState.CONNECTING, AaLinkState.SERVER_OFF, AaLinkState.CONNECTING, AaLinkState.ACTIVE), states.toList())

        // Gone again: that is said in the log once more, as the first time.
        peer.close()
        awaitState(AaLinkState.SERVER_OFF)
        assertEquals("got $log", 2, log.count { "no answer from the head unit server" in it })
    }

    // ── a session ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every input sent to Android Auto asks for its screen to be read again (the
     * focus may move with no window event, as when Waze is picked in the menu); the phone's own call
     * commands are not Android Auto inputs and ask nothing.
     */
    @Test
    fun anInputSentToAndroidAutoAsksForItsScreenToBeReadAgain() {
        val calls = CopyOnWriteArrayList<String>()
        AaScreens.rereader = object : AaScreens.Rereader {
            override fun inputSent() { calls += "input" }
            override fun projection(on: Boolean) { calls += "projection $on" }
        }
        try {
            val port = freePort()
            peerOn(port)
            val r = receiverOn(port)
            r.sendInput(AaCommand.Key(23))
            assertTrue("no session, nothing sent, nothing asked: $calls", calls.isEmpty())
            r.start()
            awaitState(AaLinkState.ACTIVE)
            r.sendInput(AaCommand.Key(23))
            await("a re-read asked after the key") { calls == listOf("input") }
            r.sendInput(AaCommand.AnswerCall)
            Thread.sleep(200)
            assertEquals(listOf("input"), calls)
        } finally {
            AaScreens.rereader = null
        }
    }

    @Test
    fun withAndroidAutoListeningOn5277TheSessionBecomesActiveAndAnswers() {
        val peer = peerOn(5277)
        // No port given: the receiver's own default.
        val r = AaReceiver(context, config, reader.surface, { states += it }).also { receiver = it }

        r.start()
        awaitState(AaLinkState.ACTIVE)

        assertEquals(listOf(AaLinkState.CONNECTING, AaLinkState.ACTIVE), states.toList())
        assertEquals(AaLinkState.ACTIVE, r.state)
        assertTrue("the fake saw the whole handshake: ${peer.events}", peer.awaitHandshake())

        // The session is read and answered, with this receiver's configuration.
        peer.send(
            Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
            Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build().toByteArray(),
        )
        val answer = next(peer, "discovery response")
        assertEquals(Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, answer.type)
        val video = Control.ServiceDiscoveryResponse.parseFrom(answer.body).servicesList.single { it.id == Channel.ID_VID }
        assertEquals(180, video.mediaSinkService.videoConfigsList.single().density)
    }

    @Test
    fun theVideoOfTheSessionIsDecodedOntoTheSurfaceGivenToTheReceiver() {
        val port = freePort()
        val peer = peerOn(port)
        receiverOn(port).start()
        awaitState(AaLinkState.ACTIVE)

        val units = TestH264Source.encode(frames = 3)
        peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(3).setConfigurationIndex(0).build().toByteArray())
        peer.send(Channel.ID_VID, type = 1, body = units.single { it.isConfig }.data)
        for (frame in units.filter { !it.isConfig }) {
            val body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + frame.data
            if (frame.isKey) peer.sendInPieces(Channel.ID_VID, type = 0, body = body, pieces = 3) else peer.send(Channel.ID_VID, type = 0, body = body)
        }

        repeat(4) { assertEquals(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, next(peer, "acknowledgement ${it + 1}").type) }
        await("a picture on the surface") { lastPicture.get() != null }
        PictureCheck.assertIsThePattern(lastPicture.get()!!)

        receiver!!.stop()
        assertTrue("the session's decoder is closed with it, got $log", log.any { "Decoder stopped: session over" in it })
    }

    @Test
    fun withAVoiceGivenGeminiHearsTheRiderAndTheSessionEndingClosesTheMicrophone() {
        // Behind the switch "Gemini pelo joystick": the receiver gives each session a microphone.
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val voice = object : VoiceSource {
            @Volatile var open = false
            override fun open(): Boolean = true.also { open = true; opened.incrementAndGet() }
            override fun read(into: ShortArray): Int {
                if (!open) return -1
                Thread.sleep(20)
                return into.size
            }
            override fun close() {
                open = false
                closed.incrementAndGet()
            }
        }
        val port = freePort()
        val peer = peerOn(port)
        AaReceiver(context, config, reader.surface, { states += it }, AaReceiver.Options(port, quick, voice = { voice }))
            .also { receiver = it }.start()
        awaitState(AaLinkState.ACTIVE)

        peer.send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(9).setConfigurationIndex(0).build().toByteArray())
        peer.send(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE, Media.MicrophoneRequest.newBuilder().setOpen(true).build().toByteArray())
        val answer = next(peer, "microphone response")
        assertEquals(Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE, answer.type)
        assertEquals("it opened", com.reau.app.aa.proto.Common.MessageStatus.STATUS_SUCCESS_VALUE, Media.MicrophoneResponse.parseFrom(answer.body).status)
        val data = next(peer, "voice")
        assertEquals(Channel.ID_MIC, data.channel)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_DATA_VALUE, data.type)

        receiver!!.stop()
        await("the session ending closed the microphone") { closed.get() == 1 }
        assertEquals(1, opened.get())
    }

    @Test
    fun theSessionWritesAnAaStatsLineEveryPeriodAndOneAtItsEnd() {
        val port = freePort()
        val peer = peerOn(port)
        receiverOn(port, quick.copy(statsEveryMs = 300)).start()
        awaitState(AaLinkState.ACTIVE)

        val units = TestH264Source.encode(frames = 3)
        peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(3).setConfigurationIndex(0).build().toByteArray())
        peer.send(Channel.ID_VID, type = 1, body = units.single { it.isConfig }.data)
        for (frame in units.filter { !it.isConfig }) peer.send(Channel.ID_VID, type = 0, body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + frame.data)

        await("a periodic aa-stats line with the session's units", timeoutMs = 5_000) {
            log.any { "aa-stats" in it && "total units=4 " in it }
        }
        receiver!!.stop()
        await("and one at the session's end") { log.any { "aa-stats" in it && "(session end)" in it } }
    }

    // ── the session ends ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun whenAndroidAutoGoesAwayTheStateIsDroppedAndASessionStartsAgainWhenItIsBack() {
        val port = freePort()
        val first = peerOn(port)
        receiverOn(port).start()
        awaitState(AaLinkState.ACTIVE)

        first.close() // the socket and the server: Android Auto is gone
        awaitState(AaLinkState.SERVER_OFF)

        val second = peerOn(port)
        awaitState(AaLinkState.ACTIVE)
        assertTrue(second.awaitHandshake())
        assertEquals(
            listOf(
                AaLinkState.CONNECTING, AaLinkState.ACTIVE, AaLinkState.DROPPED, AaLinkState.SERVER_OFF,
                AaLinkState.CONNECTING, AaLinkState.ACTIVE,
            ),
            states.toList(),
        )

        // The new session is a working one.
        second.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build().toByteArray())
        assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, next(second, "ping response").type)
    }

    @Test
    fun aNewConfigurationEndsTheSessionWithAByeAndTheNextSessionIsToldIt() {
        // The density (and resolution, margins) apply without switching off. Android Auto
        // reads them only when a session begins, so the session is ended (a bye, as switching off does) and the next
        // one, dialled at once as after any drop, is told the new values.
        val port = freePort()
        val first = peerOn(port)
        val r = receiverOn(port)
        r.start()
        awaitState(AaLinkState.ACTIVE)

        r.reconfigure(config.copy(density = 240))
        assertEquals(Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, next(first, "byebye").type)
        awaitState(AaLinkState.DROPPED)

        first.close()
        val second = peerOn(port)
        awaitState(AaLinkState.ACTIVE)
        assertTrue(second.awaitHandshake())
        second.send(
            Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
            Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build().toByteArray(),
        )
        val answer = next(second, "discovery response")
        val video = Control.ServiceDiscoveryResponse.parseFrom(answer.body).servicesList.single { it.id == Channel.ID_VID }
        assertEquals("the new session is told the new density", 240, video.mediaSinkService.videoConfigsList.single().density)
        assertEquals("never idle: the receiver kept going", false, AaLinkState.IDLE in states)
    }

    @Test
    fun aNewConfigurationWithNoSessionIsSimplyUsedByTheNextOne() {
        val port = freePort()
        val r = receiverOn(port)
        r.start()
        awaitState(AaLinkState.SERVER_OFF)
        r.reconfigure(config.copy(density = 250))
        val peer = peerOn(port)
        awaitState(AaLinkState.ACTIVE)
        assertTrue(peer.awaitHandshake())
        peer.send(
            Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
            Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build().toByteArray(),
        )
        val video = Control.ServiceDiscoveryResponse.parseFrom(next(peer, "discovery response").body).servicesList.single { it.id == Channel.ID_VID }
        assertEquals(250, video.mediaSinkService.videoConfigsList.single().density)
    }

    @Test
    fun aServerThatIsBackBeforeTheNextDialIsReconnectedWithoutPassingThroughServerOff() {
        val port = freePort()
        val first = peerOn(port)
        receiverOn(port, patient).start()
        awaitState(AaLinkState.ACTIVE)

        first.close()
        awaitState(AaLinkState.DROPPED)
        peerOn(port)
        awaitState(AaLinkState.ACTIVE)

        assertEquals(
            listOf(AaLinkState.CONNECTING, AaLinkState.ACTIVE, AaLinkState.DROPPED, AaLinkState.CONNECTING, AaLinkState.ACTIVE),
            states.toList(),
        )
    }

    @Test
    fun aHandshakeThatFailsIsADropAndTheReceiverTriesAgain() {
        val port = freePort()
        val broken = peerOn(port, FakeAndroidAutoPeer.Script(hangUpAt = FakeAndroidAutoPeer.Stage.VERSION_ANSWERED))
        receiverOn(port, patient).start()

        awaitState(AaLinkState.DROPPED)
        assertEquals("never active", listOf(AaLinkState.CONNECTING, AaLinkState.DROPPED), states.toList())

        broken.close()
        peerOn(port)
        awaitState(AaLinkState.ACTIVE)
    }

    @Test
    fun aByeByeFromAndroidAutoEndsTheSessionAsADrop() {
        val port = freePort()
        val peer = peerOn(port)
        receiverOn(port, patient).start()
        awaitState(AaLinkState.ACTIVE)

        peer.send(
            Channel.ID_CTR, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE,
            Control.ByeByeRequest.newBuilder().setReason(Control.ByeByeReason.USER_SELECTION).build().toByteArray(),
        )

        assertEquals(Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE, next(peer, "byebye response").type)
        awaitState(AaLinkState.DROPPED)
        assertTrue("the log says how the session ended, got $log", log.any { "session ended" in it && "clean=true" in it })
        // The fake reads the end of the stream: the receiver closed its socket.
        await("the socket of the ended session must be closed: ${peer.events}") { peer.failure is java.io.EOFException }
    }

    // ── stopping ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The Desligar felt slow: on the phone Android Auto answers the bye in 5 to 24 ms, yet the
     * receiver said "stopped" about 2 s later, and the walk that switches the server off waits for it. With the bye
     * answered at once, the receiver is stopped well within half a second.
     */
    @Test
    fun withTheByeAnsweredAtOnceTheReceiverStopsQuickly() {
        val port = freePort()
        peerOn(port, FakeAndroidAutoPeer.Script(answerByeAfterMs = 0))
        val r = receiverOn(port)
        r.start()
        awaitState(AaLinkState.ACTIVE)
        val begun = android.os.SystemClock.elapsedRealtime()
        r.stop()
        val took = android.os.SystemClock.elapsedRealtime() - begun
        assertTrue("stopped in $took ms (log: ${log.takeLast(15)})", took < QUICK_STOP_MS)
        assertEquals(AaLinkState.IDLE, r.state)
    }

    /**
     * Observed on a device: after the session's end the link's thread still slept the second before dialling
     * again, which a stopped receiver never does; the stop's interrupt was swallowed by the real video decoder, waiting
     * on its own thread. Swallowed here at the session's end the same way, the stop is still quick.
     */
    @Test
    fun aStoppedReceiverDoesNotWaitToDialAgainEvenWithTheInterruptSwallowed() {
        val port = freePort()
        peerOn(port, FakeAndroidAutoPeer.Script(answerByeAfterMs = 0))
        val swallowing = AaReceiver.Options(
            port, AaReceiver.Timing(redialMs = 200, afterDropMs = LONG_AFTER_DROP_MS),
            onNavigation = { if (it == NavGuidance.INACTIVE) runCatching { Thread.sleep(SWALLOW_MS) } },
        )
        val r = AaReceiver(context, config, reader.surface, { states += it }, swallowing).also { receiver = it }
        r.start()
        awaitState(AaLinkState.ACTIVE)
        val begun = android.os.SystemClock.elapsedRealtime()
        r.stop()
        val took = android.os.SystemClock.elapsedRealtime() - begun
        assertTrue("stopped in $took ms (log: ${log.takeLast(10)})", took < SWALLOW_MS + QUICK_STOP_MS)
    }

    @Test
    fun stopSaysByeByeAndGoesIdleWithoutCallingItADropAndDialsNoMore() {
        val port = freePort()
        val peer = peerOn(port)
        val r = receiverOn(port)
        r.start()
        awaitState(AaLinkState.ACTIVE)

        r.stop()

        assertEquals(AaLinkState.IDLE, r.state)
        assertEquals(listOf(AaLinkState.CONNECTING, AaLinkState.ACTIVE, AaLinkState.IDLE), states.toList())
        assertEquals(Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, next(peer, "byebye").type)

        peer.close()
        val later = peerOn(port)
        Thread.sleep(800) // four dial intervals
        assertFalse("a stopped receiver must not dial: ${later.events}", "accepted" in later.events)
        assertEquals("and reports nothing more", AaLinkState.IDLE, states.last())
    }

    @Test
    fun stopWhileWaitingForTheServerStopsTheDiallingAndStartWorksAgainAfterIt() {
        val port = freePort()
        val r = receiverOn(port)
        r.start()
        awaitState(AaLinkState.SERVER_OFF)

        r.stop()
        assertEquals(AaLinkState.IDLE, r.state)
        val idle = peerOn(port)
        Thread.sleep(800)
        assertFalse("a stopped receiver must not dial: ${idle.events}", "accepted" in idle.events)

        r.start()
        awaitState(AaLinkState.ACTIVE)
        assertEquals(
            listOf(AaLinkState.CONNECTING, AaLinkState.SERVER_OFF, AaLinkState.IDLE, AaLinkState.CONNECTING, AaLinkState.ACTIVE),
            states.toList(),
        )
    }

    @Test
    fun startingTwiceKeepsOneLoop() {
        val r = receiverOn(freePort())
        r.start()
        r.start()
        awaitState(AaLinkState.SERVER_OFF)
        Thread.sleep(500)

        assertEquals(listOf(AaLinkState.CONNECTING, AaLinkState.SERVER_OFF), states.toList())
        assertEquals(1, Thread.getAllStackTraces().keys.count { it.name == "aa-link" })
    }

    /** One session at a time: a second start must not dial beside the session that is up. */
    @Test
    fun startingAgainDuringASessionLeavesTheSessionAlone() {
        val port = freePort()
        val peer = peerOn(port)
        val r = receiverOn(port)
        r.start()
        awaitState(AaLinkState.ACTIVE)

        r.start()
        Thread.sleep(600)

        assertEquals("nothing new was reported", listOf(AaLinkState.CONNECTING, AaLinkState.ACTIVE), states.toList())
        assertEquals(AaLinkState.ACTIVE, r.state)
        assertTrue("got $log", log.any { "already running" in it })
        peer.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build().toByteArray())
        assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, next(peer, "ping response").type)
    }

    @Test
    fun stoppingAReceiverThatWasNeverStartedDoesNothing() {
        receiverOn(freePort()).stop()
        assertTrue(states.isEmpty())
    }

    // ── what is installed ──────────────────────────────────────────────────────────────────────────────────

    /** On the emulator that is a stub ("1.2.x-stub"), not the absence one might expect; on a phone, the real one. */
    @Test
    fun startLogsWhichAndroidAutoIsInstalled() {
        val line = AndroidAuto.describe(AndroidAuto.installedVersion(context))
        val ending = if (com.reau.app.TestDevice.isEmulator) "-stub is installed" else " is installed"
        assertTrue("got '$line'", line.startsWith("Android Auto ") && line.endsWith(ending))
        receiverOn(freePort()).start()
        await("the version line '$line'") { log.any { line in it } }
    }
}
