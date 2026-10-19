package com.reau.app.aa

import android.graphics.ImageFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.proto.Sensors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Day and night through the receiver: what [AaReceiver.setNight] is told reaches the fake
 * Android Auto's night sensor, at its start and at each change, in every session.
 */
@RunWith(AndroidJUnit4::class)
class AaReceiverNightTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<AaLinkState>()
    private val reader = ImageReader.newInstance(800, 480, ImageFormat.YUV_420_888, 2)
    private val port = ServerSocket(0).use { it.localPort }
    private val peers = mutableListOf<FakeAndroidAutoPeer>()
    private val receiver = AaReceiver(
        context, AaConfig(), reader.surface, { states += it },
        AaReceiver.Options(port, AaReceiver.Timing(redialMs = 200, afterDropMs = 100)),
    )

    @After
    fun tearDown() {
        receiver.stop()
        peers.forEach { it.close() }
        reader.close()
        AaLog.sink = null
    }

    private fun peer() = FakeAndroidAutoPeer(listenOn = port).also { peers += it }

    private fun awaitActive() {
        val deadline = System.currentTimeMillis() + 15_000
        while (states.lastOrNull() != AaLinkState.ACTIVE && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("states: $states (log: $log)", AaLinkState.ACTIVE, states.lastOrNull())
    }

    private fun startNightSensor(peer: FakeAndroidAutoPeer) {
        val request = Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.NIGHT).setMinUpdatePeriod(0).build()
        peer.send(com.reau.app.aa.Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE, request.toByteArray())
        assertEquals("the sensor start answered", Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, nextSensor(peer)?.type)
    }

    /** The next message on the sensor channel, skipping everything else; null if none comes in [timeoutMs]. */
    private fun nextSensor(peer: FakeAndroidAutoPeer, timeoutMs: Long = 3_000): FakeAndroidAutoPeer.Received? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val m = peer.receive(deadline - System.currentTimeMillis()) ?: return null
            if (m.channel == com.reau.app.aa.Channel.ID_SEN) return m
        }
        return null
    }

    private fun night(isNight: Boolean) = byteArrayOf(0x52, 2, 0x08, if (isNight) 1 else 0)

    @Test
    fun nightSetBeforeTheSessionIsWhatTheSensorStartsWith() {
        AaLog.sink = { log += it }
        receiver.setNight(true)
        val peer = peer()
        receiver.start()
        awaitActive()
        startNightSensor(peer)
        assertArrayEquals(night(true), nextSensor(peer)?.body)
    }

    @Test
    fun eachChangeGoesOutOnceTheSensorIsStartedAndARepeatDoesNot() {
        val peer = peer()
        receiver.start()
        awaitActive()
        startNightSensor(peer)
        assertArrayEquals("day to start with", night(false), nextSensor(peer)?.body)

        receiver.setNight(true)
        assertArrayEquals(night(true), nextSensor(peer)?.body)
        receiver.setNight(true)
        assertNull("the same value again is not sent again", nextSensor(peer, timeoutMs = 500))
        receiver.setNight(false)
        assertArrayEquals(night(false), nextSensor(peer)?.body)
    }

    @Test
    fun aNewSessionStartsWithTheValueOfTheOldOne() {
        val first = peer()
        receiver.start()
        awaitActive()
        receiver.setNight(true)
        first.close()
        peers.clear()

        val second = peer()
        val deadline = System.currentTimeMillis() + 15_000
        while (!second.awaitHandshake(100) && System.currentTimeMillis() < deadline) Unit
        awaitActive()
        startNightSensor(second)
        assertArrayEquals("still night in the new session", night(true), nextSensor(second)?.body)
    }
}
