package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.AaScreens
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.FakeAndroidAutoVideo
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.video.TestH264Source
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inside the service: while Android Auto's side of the projection runs, the
 * screen re-reader (the accessibility service on the phone) is told so, and told again when it stops, so it reads
 * Android Auto's screen every second in between (AaScreenRereads).
 */
@RunWith(AndroidJUnit4::class)
class ScreenRereadEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val aaPort = ServerSocket(0).use { it.localPort }
    private val androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
    private val video = FakeAndroidAutoVideo(androidAuto, TestH264Source.encode(frames = 15, keyframeEvery = 15))
    private val dash = FakeDashPeer().apply { autoAck = true }
    private val told = CopyOnWriteArrayList<String>()

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) = onReady("DashFake", null)
        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the service gone", 10_000) { !serviceRunning() }
        AaScreens.rereader = null
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
        assertTrue("$what (projection: ${ProjectionService.projection.value}, told: $told)", condition())
    }

    @Test
    fun theRereaderIsToldWhileAndroidAutosSideRunsAndWhenItStops() {
        AaScreens.rereader = object : AaScreens.Rereader {
            override fun inputSent() { told += "input" }
            override fun projection(on: Boolean) { told += "projection $on" }
        }
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = AaConfig(),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                ),
            )
        }
        ProjectionService.start(context)
        await("streaming", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
        assertTrue(AaScreens.projecting)
        assertEquals(listOf("projection true"), told.filter { it.startsWith("projection") })

        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("told it stopped", 5_000) { told.filter { it.startsWith("projection") } == listOf("projection true", "projection false") }
        assertTrue(!AaScreens.projecting)
    }
}
