package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.Channel
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.proto.Sensors
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * Day turning to night inside the service: the projection asks its day/night clock again and
 * again, and the fake Android Auto's night sensor gets the change. The clock here is the test's switch.
 */
@RunWith(AndroidJUnit4::class)
class NightEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val aaPort = ServerSocket(0).use { it.localPort }
    private val androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
    private val dash = FakeDashPeer().apply { autoAck = true }
    @Volatile private var nightNow = false

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) = onReady("DashFake", null)
        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        androidAuto.close()
        dash.close()
        ProjectionService.sidesFactory = originalSides
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$what (projection: ${ProjectionService.projection.value})", condition())
    }

    private fun nextSensor(timeoutMs: Long = 5_000): FakeAndroidAutoPeer.Received? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val m = androidAuto.receive(deadline - SystemClock.elapsedRealtime()) ?: return null
            if (m.channel == Channel.ID_SEN) return m
        }
        return null
    }

    @Test
    fun theFakeAndroidAutoGetsTheChangeFromDayToNight() {
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                    night = { nightNow },
                    nightCheckMs = 200,
                ),
            )
        }
        ProjectionService.start(context)
        await("Android Auto up", 30_000) { ProjectionService.projection.value.androidAuto == AaLinkState.ACTIVE }

        val request = Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.NIGHT).setMinUpdatePeriod(0).build()
        androidAuto.send(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE, request.toByteArray())
        assertEquals(Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, nextSensor()?.type)
        assertArrayEquals("day when the sensor starts", byteArrayOf(0x52, 2, 0x08, 0), nextSensor()?.body)

        nightNow = true
        assertArrayEquals("night once the clock says so", byteArrayOf(0x52, 2, 0x08, 1), nextSensor()?.body)
        nightNow = false
        assertArrayEquals("and day again", byteArrayOf(0x52, 2, 0x08, 0), nextSensor()?.body)
    }
}
