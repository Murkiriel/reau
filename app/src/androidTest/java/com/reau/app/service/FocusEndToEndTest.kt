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
import com.reau.app.aa.proto.Input
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.settings.REauSettings
import com.reau.app.video.TestH264Source
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android Auto's focus taken away by itself when nothing is touched for a while, inside the
 * service: after a joystick press reaches Android Auto and the quiet spell passes (shortened here), Android Auto gets one
 * touch, cancelled at once; with the switch off, none.
 */
@RunWith(AndroidJUnit4::class)
class FocusEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val aaPort = ServerSocket(0).use { it.localPort }
    private val androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
    private val video = FakeAndroidAutoVideo(androidAuto, TestH264Source.encode(frames = 15, keyframeEvery = 15))
    private val dash = FakeDashPeer().apply { autoAck = true }

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

    private fun start(clearFocus: Boolean) {
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = AaConfig(touch = true),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                    settings = REauSettings(clearAaFocus = clearFocus),
                    focusIdleMs = 1_500,
                ),
            )
        }
        ProjectionService.start(context)
        await("streaming", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
    }

    /** What the head unit sent on the input channel within [ms]: "key", "wheel" or "touch <action> x,y". */
    private fun inputsFor(ms: Long): List<String> {
        val out = mutableListOf<String>()
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            val m = androidAuto.receive(timeoutMs = 200) ?: continue
            if (m.channel != Channel.ID_INP || m.type != Input.MsgType.EVENT_VALUE) continue
            val report = Input.InputReport.parseFrom(m.body)
            when {
                report.hasTouchEvent() -> report.touchEvent.let { t ->
                    out += "touch ${t.action.name} ${t.getPointerData(0).x},${t.getPointerData(0).y}"
                }
                report.hasKeyEvent() -> out += "key"
                report.hasRelativeEvent() -> out += "wheel"
            }
        }
        return out
    }

    @Test
    fun aQuietSpellAfterTheJoystickEndsInOneCancelledTouch() {
        start(clearFocus = true)
        dash.pressButton(0x13) // the map mode's right: the wheel
        val inputs = inputsFor(5_000)
        assertTrue("the press went: $inputs", inputs.firstOrNull() == "wheel")
        assertEquals("then one tap: $inputs", listOf("touch TOUCH_ACTION_DOWN 400,240", "touch TOUCH_ACTION_CANCEL 400,240"),
            inputs.filter { it.startsWith("touch") })
    }

    @Test
    fun withTheSwitchOffNoTap() {
        start(clearFocus = false)
        dash.pressButton(0x13)
        val inputs = inputsFor(5_000)
        assertTrue("the press went: $inputs", "wheel" in inputs)
        assertTrue("no tap: $inputs", inputs.none { it.startsWith("touch") })
    }
}
