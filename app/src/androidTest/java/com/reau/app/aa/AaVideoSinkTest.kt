package com.reau.app.aa

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.proto.Media
import com.reau.app.TestDevice
import com.reau.app.video.H264TestDecoder
import com.reau.app.video.PictureCheck
import com.reau.app.video.SoftwareH264Decoder
import com.reau.app.video.TestH264Source
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The video side of a whole session: the fake Android Auto sends H.264 the way the real one does (a codec
 * configuration, then pictures, whole or cut in pieces) and the receiver decodes it onto a surface, acknowledges
 * every message once and asks for a keyframe, by cycling video focus, when the stream breaks.
 */
@RunWith(AndroidJUnit4::class)
class AaVideoSinkTest {
    /**
     * The pictures are read back through an ImageReader: from the software decoder, which every device can read (see the rule).
     * Three tests below need a decoder that gives a picture at once (the software one holds the first frames back), so they
     * choose nothing and run only where that decoder is safe to read: the emulator's.
     */
    @get:org.junit.Rule val softwareDecoder = SoftwareH264Decoder(inEveryTest = false)

    private fun useTheSoftwareDecoder() = softwareDecoder.choose()

    private fun needsADecoderThatAnswersAtOnce() = assumeTrue(
        "needs a decoder that outputs the first picture at once: the software one holds frames back, and reading the pictures of " +
            "some phones' hardware decoders abort the test process (Image_createSurfacePlanes), so only the emulator's runs it",
        TestDevice.isEmulator,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val config = AaConfig()
    private val readerThread = HandlerThread("test-image-reader").apply { start() }
    private val reader = ImageReader.newInstance(TestPattern.WIDTH, TestPattern.HEIGHT, ImageFormat.YUV_420_888, 4)
    private val lastPicture = AtomicReference<H264TestDecoder.Picture?>()
    private val pictures = AtomicInteger()
    private lateinit var peer: FakeAndroidAutoPeer
    private lateinit var connection: SocketAccessoryConnection
    private lateinit var transport: AapTransport
    private lateinit var sink: AaVideoSink

    @Before
    fun startSession() {
        AaLog.sink = { log += it }
        ConscryptInitializer.initialize()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use {
                lastPicture.set(H264TestDecoder.pictureOf(it))
                pictures.incrementAndGet()
            }
        }, Handler(readerThread.looper))

        peer = FakeAndroidAutoPeer()
        connection = SocketAccessoryConnection(Socket(peer.address, peer.port))
        transport = AapTransport(context)
        transport.onQuit = { }
        assertTrue("handshake failed: $log", transport.startHandshake(connection))
        assertTrue(peer.awaitHandshake())
        sink = AaVideoSink { transport.triggerFocusCycleRecovery() }
        sink.setSurface(reader.surface)
        transport.startReading(AapMessageHandlerType(transport, config, sink::process))
        peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(SESSION).setConfigurationIndex(0).build().toByteArray())
    }

    @After
    fun tearDown() {
        transport.quit()
        connection.disconnect()
        peer.close()
        sink.release()
        reader.close()
        readerThread.quitSafely()
        AaLog.sink = null
    }

    // ── what the fake sends ────────────────────────────────────────────────────────────────────────────────

    private val codecConfig = video.single { it.isConfig }
    private val frames = video.filter { !it.isConfig }

    /** A picture message's body: the 8-byte timestamp and the H.264. */
    private fun body(frame: TestH264Source.Unit) = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + frame.data

    private fun sendConfig() = peer.send(Channel.ID_VID, type = 1, body = codecConfig.data)

    private fun sendWhole(frame: TestH264Source.Unit) = peer.send(Channel.ID_VID, type = 0, body = body(frame))

    private fun sendInPieces(frame: TestH264Source.Unit, pieces: Int, drop: Set<Int> = emptySet()) =
        peer.sendInPieces(Channel.ID_VID, type = 0, body = body(frame), pieces = pieces, drop = drop)

    // ── what comes back ────────────────────────────────────────────────────────────────────────────────────

    private fun next(what: String): FakeAndroidAutoPeer.Received {
        val received = peer.receive()
        assertNotNull("$what: nothing came (log: $log)", received)
        return received!!
    }

    private fun assertAck(what: String) {
        val ack = next(what)
        assertEquals(what, Channel.ID_VID, ack.channel)
        assertEquals(what, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack.type)
        assertArrayEquals("$what: session_id = $SESSION, ack = 1", byteArrayOf(0x08, SESSION.toByte(), 0x10, 1), ack.body)
    }

    private fun assertFocus(what: String, body: ByteArray) {
        val focus = next(what)
        assertEquals(what, Channel.ID_VID, focus.channel)
        assertEquals(what, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, focus.type)
        assertArrayEquals(what, body, focus.body)
    }

    private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (log: $log)", condition())
    }

    private fun assertShowsThePattern(picture: H264TestDecoder.Picture) {
        val wrong = mutableListOf<String>()
        for (y in 40 until TestPattern.HEIGHT step 80) {
            for (x in 50 until TestPattern.WIDTH step 100) {
                val expected = PictureCheck.name(TestPattern.colorAt(x, y))
                val actual = PictureCheck.name(picture.colorAt(x, y))
                if (actual != expected) wrong += "($x,$y) $actual, expected $expected"
            }
        }
        assertTrue("${wrong.size} of 48 cells wrong: $wrong", wrong.isEmpty())
    }

    // ── the tests ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun videoSentWholeAndInPiecesReachesTheSurfaceAndEveryMessageIsAcknowledgedOnce() {
        useTheSoftwareDecoder()
        sendConfig()
        sendInPieces(frames[0], pieces = 4) // the keyframe: flags 9, 8, 8, 10
        frames.subList(1, 4).forEach { sendWhole(it) } // flags 11
        sendInPieces(frames[4], pieces = 2) // the second keyframe: flags 9, 10
        frames.drop(5).forEach { sendWhole(it) }

        repeat(1 + frames.size) { assertAck("acknowledgement ${it + 1} of ${1 + frames.size}") }
        assertNull("one acknowledgement per message, not per piece", peer.receive(300))

        // How many pictures the test's reader sees of a burst is its own business: it keeps only the latest.
        await("the picture must reach the surface") { pictures.get() >= 1 }
        assertShowsThePattern(checkNotNull(lastPicture.get()))
        assertTrue("an intact stream asks for no keyframe, got $log", log.none { "requesting a keyframe" in it })
    }

    @Test
    fun aFrameThatLostItsFirstPieceIsThrownAwayAndAKeyframeIsAskedForByCyclingVideoFocus() {
        needsADecoderThatAnswersAtOnce()
        sendConfig()
        sendInPieces(frames[0], pieces = 3)
        assertAck("configuration")
        assertAck("keyframe")

        sendInPieces(frames[4], pieces = 3, drop = setOf(0))

        assertFocus("video focus taken away", byteArrayOf(0x08, 2, 0x10, 0)) // native, as an answer
        assertFocus("and given back", byteArrayOf(0x08, 1, 0x10, 1)) // projected, unsolicited
        assertNull("pieces with no beginning earn no acknowledgement, and the request is made once", peer.receive(400))
        assertTrue("got $log", log.any { "ORPHANED_FRAGMENT" in it })

        // Android Auto answers the focus cycle with a keyframe: the stream goes on.
        val before = pictures.get()
        sendInPieces(frames[4], pieces = 3)
        assertAck("the keyframe after the cycle")
        await("pictures again") { pictures.get() > before }
        assertShowsThePattern(checkNotNull(lastPicture.get()))
    }

    @Test
    fun aFrameThatLostItsLastPieceIsNoticedWhenTheNextOneBegins() {
        useTheSoftwareDecoder()
        sendConfig()
        sendInPieces(frames[0], pieces = 3)
        assertAck("configuration")
        assertAck("keyframe")

        sendInPieces(frames[4], pieces = 3, drop = setOf(2))
        assertAck("the first piece looked fine")
        assertNull("nothing is wrong yet", peer.receive(300))

        sendWhole(frames[5])
        assertFocus("video focus taken away", byteArrayOf(0x08, 2, 0x10, 0))
        assertAck("the frame that showed the gap is itself whole")
        assertFocus("and given back", byteArrayOf(0x08, 1, 0x10, 1))
        assertTrue("got $log", log.any { "TRUNCATED_PREVIOUS" in it })
    }

    /** Bytes keep coming and no picture comes out: the decoder's own watchdog. */
    @Test
    fun aDecoderThatStopsGivingPicturesIsRestartedAndAKeyframeIsAskedFor() {
        needsADecoderThatAnswersAtOnce()
        sendConfig()
        sendInPieces(frames[0], pieces = 3)
        assertAck("configuration")
        assertAck("keyframe")
        await("the first picture") { pictures.get() >= 1 }

        // Filler-data NAL units: valid H.264 with no picture in it. Sent as Android Auto sends: the next message
        // goes out when the one before was acknowledged (its window is 12; one at a time is the strict case).
        val filler = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 0x0C, 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte())
        // The read no longer waits for the decoder: with the codec stuck its queue fills first, and the
        // dropped frame may already ask for a keyframe before the decoder's watchdog acts. Fillers go on until the
        // watchdog has acted, and every focus message on the way is kept.
        val focus = mutableListOf<ByteArray>()
        var acknowledged = 0
        val until = System.currentTimeMillis() + 10_000
        while (log.none { "Decoder stall detected" in it } && System.currentTimeMillis() < until) {
            peer.send(Channel.ID_VID, type = 0, body = filler)
            Thread.sleep(50)
            while (true) {
                val received = peer.receive(1_000) ?: break
                if (received.type == Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE) focus += received.body
                if (received.type == Media.MsgType.MEDIA_MESSAGE_ACK_VALUE) {
                    acknowledged++
                    break
                }
            }
        }
        // The watchdog restarts the decoder at its next input, and that asks for a keyframe too.
        peer.send(Channel.ID_VID, type = 0, body = filler)
        val drainUntil = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < drainUntil) {
            val received = peer.receive(200) ?: continue
            if (received.type == Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE) focus += received.body
        }

        assertTrue("got $log", log.any { "Decoder stall detected" in it })
        val lost = byteArrayOf(0x08, 2, 0x10, 0)
        val back = byteArrayOf(0x08, 1, 0x10, 1)
        assertTrue("the receiver must cycle video focus ($acknowledged fillers acknowledged, log: $log)",
            focus.any { it.contentEquals(lost) })
        assertTrue("focus given back (${focus.map { it.toList() }})", focus.any { it.contentEquals(back) })

        // The keyframe Android Auto sends next is decoded by the restarted decoder.
        val before = pictures.get()
        sendConfig()
        sendInPieces(frames[0], pieces = 3)
        await("pictures after the restart (had $before)") { pictures.get() > before }
        assertShowsThePattern(checkNotNull(lastPicture.get()))
    }

    @Test
    fun setupAndFocusMessagesOnTheVideoChannelStillReachTheControlLayer() {
        useTheSoftwareDecoder()
        peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, Media.MediaSetupRequest.newBuilder().setType(3).build().toByteArray())

        val setup = next("config response")
        assertEquals(Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, setup.type)
        assertEquals(12, Media.Config.parseFrom(setup.body).maxUnacked)
        assertFocus("video focus", byteArrayOf(0x08, 1, 0x10, 1))
        assertTrue("that was no broken stream, got $log", log.none { "requesting a keyframe" in it })
    }

    /** Android Auto always sends the configuration first; if it ever did not, the decoder opens with the size asked for. */
    @Test
    fun aPictureBeforeAnyConfigurationOpensTheDecoderWithTheNegotiatedSize() {
        useTheSoftwareDecoder()
        sendWhole(frames[1])
        assertAck("the picture")
        await("the decoder must open") { log.any { "Codec initialized" in it } }
        assertTrue("got $log", log.any { "Fallback to negotiated dimensions: 800x480" in it })
    }

    @Test
    fun releaseStopsTheDecoderAndForgetsAFrameInProgress() {
        needsADecoderThatAnswersAtOnce()
        sendConfig()
        sendInPieces(frames[0], pieces = 3)
        assertAck("configuration")
        assertAck("keyframe")
        await("the first picture") { pictures.get() >= 1 }
        sendInPieces(frames[4], pieces = 3, drop = setOf(1, 2)) // a frame left open
        assertAck("its first piece")

        sink.release()

        assertTrue("got $log", log.any { "Decoder stopped: session over" in it })
        sendWhole(frames[5])
        assertAck("a frame after the release")
        assertNull("the frame left open was forgotten, not counted as a broken stream", peer.receive(400))
    }

    private companion object {
        const val SESSION = 7

        /** Ten frames of the test pattern, keyframes at 0 and 4. Encoded once for the whole class. */
        val video: List<TestH264Source.Unit> by lazy { TestH264Source.encode(frames = 10, keyframeEvery = 4) }
    }
}
