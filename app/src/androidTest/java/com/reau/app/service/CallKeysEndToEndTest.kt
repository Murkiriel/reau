package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.Channel
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.FakeAndroidAutoVideo
import com.reau.app.aa.ServiceDiscoveryResponse
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.phone.CallState
import com.reau.app.video.TestH264Source
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A WhatsApp call was not answered by the joystick. Inside
 * the service: with a call ringing, the joystick's left reaches Android Auto as the car's call key (5) and its right
 * as the end-call key (6), and the system's Telecom is still asked after each, as before.
 */
@RunWith(AndroidJUnit4::class)
class CallKeysEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val aaPort = ServerSocket(0).use { it.localPort }
    private val androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
    private val video = FakeAndroidAutoVideo(androidAuto, TestH264Source.encode(frames = 15, keyframeEvery = 15))
    private val dash = FakeDashPeer().apply { autoAck = true }
    private val answered = AtomicInteger()
    private val ended = AtomicInteger()

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) = onReady("DashFake", null)
        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the service gone", 10_000) { !serviceRunning() }
        video.close()
        androidAuto.close()
        dash.close()
        ProjectionService.sidesFactory = originalSides
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun serviceRunning(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == ProjectionService::class.java.name }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$what (projection: ${ProjectionService.projection.value})", condition())
    }

    /** The next key pressed down on Android Auto's input channel (each key goes as down then up), or null within [timeoutMs]. */
    private fun nextKey(timeoutMs: Long = 10_000): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val m = androidAuto.receive(500) ?: continue
            if (m.channel == Channel.ID_INP && m.type == com.reau.app.aa.proto.Input.MsgType.EVENT_VALUE) {
                val report = com.reau.app.aa.proto.Input.InputReport.parseFrom(m.body)
                if (report.hasKeyEvent() && report.keyEvent.getKeys(0).down) return report.keyEvent.getKeys(0).keycode
            }
        }
        return null
    }

    @Test
    fun aRingingCallIsAnsweredAndEndedThroughAndroidAutosPhoneKeys() {
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = AaConfig(),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                    calls = LiveSides.Setup.Calls(state = { CallState.RINGING }, answer = { answered.incrementAndGet() }, end = { ended.incrementAndGet() }),
                ),
            )
        }
        ProjectionService.start(context)
        await("streaming", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }

        dash.pressButton(0x14) // the joystick's left: answer
        assertEquals("the call key reached Android Auto", ServiceDiscoveryResponse.KEY_CALL, nextKey())
        await("Telecom asked too", 3_000) { answered.get() == 1 }

        Thread.sleep(1_500) // past the joystick's guard after a handled call
        dash.pressButton(0x13) // the joystick's right: reject
        assertEquals("the end-call key reached Android Auto", ServiceDiscoveryResponse.KEY_ENDCALL, nextKey())
        await("Telecom asked too", 3_000) { ended.get() == 1 }
    }
}
