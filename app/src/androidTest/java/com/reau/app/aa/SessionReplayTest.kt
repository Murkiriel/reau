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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The session recording, end to end: a session with the fake Android Auto is recorded by the receiver, and the
 * recording, replayed into a fresh handler on a second session, gets from it the answers the live one got.
 */
@RunWith(AndroidJUnit4::class)
class SessionReplayTest {
    /** The pictures are read back through an ImageReader: from the software decoder, which every device can read (see the rule). */
    @get:org.junit.Rule val softwareDecoder = SoftwareH264Decoder()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<AaLinkState>()
    private val config = AaConfig(density = 180)
    private val folder = File(context.cacheDir, "session_replay_test")
    private val readerThread = HandlerThread("test-image-reader").apply { start() }
    private val closeables = mutableListOf<AutoCloseable>()
    private var receiver: AaReceiver? = null

    @Before
    fun setUp() {
        AaLog.sink = { log += it }
        ConscryptInitializer.initialize()
        folder.deleteRecursively()
    }

    @After
    fun tearDown() {
        receiver?.stop()
        closeables.reversed().forEach { runCatching { it.close() } }
        readerThread.quitSafely()
        folder.deleteRecursively()
        AaLog.sink = null
    }

    /** A surface the test reads back, and the last picture that reached it. */
    private fun surface(): Pair<ImageReader, AtomicReference<H264TestDecoder.Picture?>> {
        val last = AtomicReference<H264TestDecoder.Picture?>()
        val reader = ImageReader.newInstance(TestPattern.WIDTH, TestPattern.HEIGHT, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { last.set(H264TestDecoder.pictureOf(it)) } }, Handler(readerThread.looper))
        closeables += reader
        return reader to last
    }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (states: $states, log: $log)", condition())
    }

    /** What a short session of Android Auto sends; returns how many messages that is for the handler. */
    private fun play(peer: FakeAndroidAutoPeer): Int {
        val video = TestH264Source.encode(frames = 4)
        fun control(type: Int, body: com.google.protobuf.Message, channel: Int = Channel.ID_CTR, flags: Int = 0x0B) =
            peer.send(channel, type, body.toByteArray(), flags)

        control(Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE, Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build())
        control(Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE, Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(Channel.ID_VID).build(), Channel.ID_VID, 0x0F)
        control(Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE, Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(Channel.ID_SEN).build(), Channel.ID_SEN, 0x0F)
        control(Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, Media.MediaSetupRequest.newBuilder().setType(3).build(), Channel.ID_VID)
        control(Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(7).setConfigurationIndex(0).build(), Channel.ID_VID)
        peer.send(Channel.ID_VID, type = 1, body = video.single { it.isConfig }.data)
        val frames = video.filter { !it.isConfig }
        peer.sendInPieces(Channel.ID_VID, type = 0, body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + frames[0].data, pieces = 3)
        frames.drop(1).forEach { peer.send(Channel.ID_VID, type = 0, body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + it.data) }
        control(Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build())
        return 6 + 3 + 3 + 1
    }

    /** Everything the head unit answered, as "channel/type" → how many, once it has been quiet for a while. */
    private fun answers(peer: FakeAndroidAutoPeer): Map<String, Int> {
        val seen = mutableListOf<String>()
        while (true) {
            val received = peer.receive(1_000) ?: break
            seen += "${received.channel}/${received.type}"
        }
        return seen.groupingBy { it }.eachCount().toSortedMap()
    }

    @Test
    fun aRecordedSessionReplayedIntoAFreshHandlerGetsTheAnswersTheLiveSessionGot() {
        // ── live, recorded ──
        val port = ServerSocket(0).use { it.localPort }
        val livePeer = FakeAndroidAutoPeer(listenOn = port).also { closeables += it }
        val (liveReader, livePicture) = surface()
        receiver = AaReceiver(
            context, config, liveReader.surface, { states += it },
            AaReceiver.Options(port, recording = AaReceiver.Recording(folder)),
        ).also { it.start() }
        await("the live session") { states.lastOrNull() == AaLinkState.ACTIVE }

        val sent = play(livePeer)
        val live = answers(livePeer)
        await("the live picture", 3_000) { livePicture.get() != null }
        receiver!!.stop()

        val files = folder.listFiles().orEmpty()
        assertEquals("one recording in the folder (log: $log)", 1, files.size)
        val file = files.single()
        assertTrue("named as a recording: ${file.name}", Regex("""reau_\d{8}_\d{6}_\d{3}\.aasession""").matches(file.name))
        val recording = SessionRecorder.read(file)
        assertFalse("stop closes the file on a whole message", recording.truncated)
        assertEquals("every message the handler got, each piece of a frame on its own", sent, recording.messages.size)
        assertTrue("got $log", log.any { "session recording closed: $sent messages" in it })

        // ── replayed ──
        val replayPeer = FakeAndroidAutoPeer().also { closeables += it }
        val connection = SocketAccessoryConnection(Socket(replayPeer.address, replayPeer.port))
        val transport = AapTransport(context)
        transport.onQuit = { }
        closeables += AutoCloseable {
            transport.quit()
            connection.disconnect()
        }
        assertTrue("handshake failed: $log", transport.startHandshake(connection))
        assertTrue(replayPeer.awaitHandshake())
        val (replayReader, replayPicture) = surface()
        val sink = AaVideoSink { transport.triggerFocusCycleRecovery() }
        closeables += AutoCloseable { sink.release() }
        sink.setSurface(replayReader.surface)
        val handler = AapMessageHandlerType(transport, config, sink::process)
        transport.startReading(handler)

        assertEquals(sent, SessionReplayer.replay(recording, handler))
        val replayed = answers(replayPeer)

        assertEquals("the same answers, channel by channel and type by type", live, replayed)
        // And they are the answers of a real session, not two empty lists agreeing with each other.
        assertEquals("one acknowledgement per video message: the configuration and four frames", 5, live["${Channel.ID_VID}/${Media.MsgType.MEDIA_MESSAGE_ACK_VALUE}"])
        assertEquals(1, live["${Channel.ID_CTR}/${Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE}"])
        assertEquals(1, live["${Channel.ID_CTR}/${Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE}"])
        await("the replayed picture", 3_000) { replayPicture.get() != null }
        PictureCheck.assertIsThePattern(replayPicture.get()!!)
    }

    @Test
    fun withoutTheOptionNoFileIsWritten() {
        val port = ServerSocket(0).use { it.localPort }
        val peer = FakeAndroidAutoPeer(listenOn = port).also { closeables += it }
        val (reader, _) = surface()
        receiver = AaReceiver(context, config, reader.surface, { states += it }, AaReceiver.Options(port)).also { it.start() }
        await("the session") { states.lastOrNull() == AaLinkState.ACTIVE }

        play(peer)
        answers(peer)
        receiver!!.stop()

        assertFalse("no recording folder: ${folder.list()?.toList()}", folder.exists())
        assertTrue("got $log", log.none { "session recording" in it })
    }

    @Test
    fun theLimitOfTheOptionIsTheLimitOfTheFile() {
        val port = ServerSocket(0).use { it.localPort }
        val peer = FakeAndroidAutoPeer(listenOn = port).also { closeables += it }
        val (reader, _) = surface()
        // A ping is 14 bytes in the file, after 13 of header: three fit in 60, the fourth does not.
        val options = AaReceiver.Options(port, recording = AaReceiver.Recording(folder, maxBytes = 60))
        receiver = AaReceiver(context, config, reader.surface, { states += it }, options).also { it.start() }
        await("the session") { states.lastOrNull() == AaLinkState.ACTIVE }

        repeat(5) {
            peer.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build().toByteArray())
            assertEquals("the session goes on past the end of the recording", Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, peer.receive()?.type)
        }
        receiver!!.stop()

        val file = folder.listFiles().orEmpty().single()
        assertEquals(55L, file.length())
        assertEquals(3, SessionRecorder.read(file).messages.size)
        assertTrue("got $log", log.any { "session recording full: 3 messages" in it })
    }

    @Test
    fun everySessionOfTheReceiverGetsItsOwnFile() {
        val port = ServerSocket(0).use { it.localPort }
        val first = FakeAndroidAutoPeer(listenOn = port).also { closeables += it }
        val (reader, _) = surface()
        receiver = AaReceiver(
            context, config, reader.surface, { states += it },
            AaReceiver.Options(port, AaReceiver.Timing(redialMs = 200, afterDropMs = 100), AaReceiver.Recording(folder)),
        ).also { it.start() }
        await("the first session") { states.lastOrNull() == AaLinkState.ACTIVE }
        first.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build().toByteArray())
        assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, first.receive()?.type)

        first.close()
        await("the drop") { AaLinkState.SERVER_OFF in states }
        await("the first recording closed when its session ended") { log.any { "session recording closed: 1 messages" in it } }
        val second = FakeAndroidAutoPeer(listenOn = port).also { closeables += it }
        await("the second session") { states.lastOrNull() == AaLinkState.ACTIVE }
        repeat(2) {
            second.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, Control.PingRequest.newBuilder().setTimestamp(1).build().toByteArray())
            assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, second.receive()?.type)
        }
        receiver!!.stop()

        val files = folder.listFiles().orEmpty().sortedBy { it.name }
        assertEquals("one file per session: ${files.map { it.name }}", 2, files.size)
        assertEquals(listOf(1, 2), files.map { SessionRecorder.read(it).messages.size })
    }
}
