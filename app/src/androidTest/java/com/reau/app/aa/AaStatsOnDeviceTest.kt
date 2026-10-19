package com.reau.app.aa

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import com.reau.app.aa.proto.Sensors
import com.reau.app.video.TestH264Source
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On the emulator: a session against the fake Android Auto
 * (the same peer FakeAndroidAutoVideo drives) is counted before the decoder, and the counts are what the fake sent,
 * the pieces it left out included.
 */
@RunWith(AndroidJUnit4::class)
class AaStatsOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val config = AaConfig()
    private val readerThread = HandlerThread("test-image-reader").apply { start() }
    private val reader = ImageReader.newInstance(TestPattern.WIDTH, TestPattern.HEIGHT, ImageFormat.YUV_420_888, 4)
    private val stats = AaStats()
    private lateinit var peer: FakeAndroidAutoPeer
    private lateinit var connection: SocketAccessoryConnection
    private lateinit var transport: AapTransport
    private lateinit var sink: AaVideoSink

    @Before
    fun startSession() {
        AaLog.sink = { log += it }
        ConscryptInitializer.initialize()
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(readerThread.looper))
        peer = FakeAndroidAutoPeer()
        connection = SocketAccessoryConnection(Socket(peer.address, peer.port))
        transport = AapTransport(context)
        transport.onQuit = { }
        transport.stats = stats
        assertTrue("handshake failed: $log", transport.startHandshake(connection))
        assertTrue(peer.awaitHandshake())
        sink = AaVideoSink(stats) { transport.triggerFocusCycleRecovery() }
        sink.setSurface(reader.surface)
        transport.startReading(AaStatsHandler(stats, AapMessageHandlerType(transport, config, sink::process)))
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

    private fun body(frame: TestH264Source.Unit) = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + frame.data

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (log: $log)", condition())
    }

    @Test
    fun theCountsAreWhatTheFakeSentWithThePiecesItLeftOut() {
        val frames = video.filter { !it.isConfig }
        peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(7).setConfigurationIndex(0).build().toByteArray())
        peer.send(Channel.ID_VID, type = 1, body = video.single { it.isConfig }.data) // a unit, not a keyframe
        peer.sendInPieces(Channel.ID_VID, type = 0, body = body(frames[0]), pieces = 3) // keyframe, rebuilt
        frames.subList(1, 4).forEach { peer.send(Channel.ID_VID, type = 0, body = body(it)) } // three whole
        peer.sendInPieces(Channel.ID_VID, type = 0, body = body(frames[4]), pieces = 3, drop = setOf(0)) // lost: no head
        peer.sendInPieces(Channel.ID_VID, type = 0, body = body(frames[4]), pieces = 3) // keyframe, rebuilt
        peer.sendInPieces(Channel.ID_VID, type = 0, body = body(frames[5]), pieces = 3, drop = setOf(2)) // lost: no tail
        peer.send(Channel.ID_VID, type = 0, body = body(frames[6])) // whole: shows the gap before it

        await("all sixteen video messages counted") { stats.messagesByChannel()[Channel.ID_VID] == 16L }
        val t = stats.totals()
        assertEquals("units: the configuration, two rebuilt, four whole", 7, t.units)
        assertEquals("keyframes", 2, t.keyframes)
        assertEquals("rebuilt from pieces", 2, t.assembled)
        assertEquals("lost: one without its first piece, one without its last", 2, t.lost)
        await("video focus cycled once for the broken stream (asked at most once a second)") { stats.totals().focus == 2L }
        await("pictures out of the decoder") { stats.totals().decoded >= 1 }
        val line = stats.windowLine()
        assertTrue(line, line.contains(" pieces=10 ") && line.contains(" lost=2 ") && !line.contains("decode=-"))
    }

    @Test
    fun aSensorAndroidAutoAsksForIsCounted() {
        peer.send(
            Channel.ID_SEN, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE,
            Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(Channel.ID_SEN).build().toByteArray(), flags = 0x0F,
        )
        peer.send(
            Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE,
            Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.DRIVING_STATUS).setMinUpdatePeriod(0).build().toByteArray(),
        )
        await("the sensor request counted") { stats.totals().sensor == 1L }
        assertEquals(2L, stats.messagesByChannel()[Channel.ID_SEN])
    }

    private companion object {
        val video: List<TestH264Source.Unit> by lazy { TestH264Source.encode(frames = 10, keyframeEvery = 4) }
    }
}
