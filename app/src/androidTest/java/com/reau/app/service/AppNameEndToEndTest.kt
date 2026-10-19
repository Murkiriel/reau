package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.AaScreenRule
import com.reau.app.aa.AaScreens
import com.reau.app.aa.AapCards
import com.reau.app.aa.Channel
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.FakeAndroidAutoVideo
import com.reau.app.aa.proto.MediaPlayback
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.video.TestH264Source
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's name never came right, so the dash's title is now REau, always. Before, inside the
 * service: with Android Auto's media screen shown and the music app its playback status names, the dash's route card
 * says that app; with the menu shown, REau. The screen shown is fed to the process' watcher as the accessibility
 * service feeds it on the phone.
 */
@RunWith(AndroidJUnit4::class)
class AppNameEndToEndTest {
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
        assertTrue("$what (projection: ${ProjectionService.projection.value}, titles at the dash: ${titles().takeLast(5)})", condition())
    }

    /** The route cards' titles (05 01) the dash got. */
    private fun titles(): List<String> = dash.receivedSinceHandshake()
        .filter { it.type == 0x05 && it.sub == 0x01 }.map { String(it.value, Charsets.UTF_8).trimEnd('\u0000') }

    /** Android Auto shows the display of [service] (one of its own), held long enough to count. */
    private fun shown(id: Int, service: String) {
        val display = AaScreenRule.Display(id, "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/x.$service}", true)
        val now = SystemClock.elapsedRealtime()
        AaScreens.watch.update(listOf(display), now)
        AaScreens.watch.update(listOf(display), now + AaScreens.HOLD_MS)
    }

    /**
     * The app's name never came right, neither on the dash nor in the notification; the dash says
     * REau, whatever Android Auto shows or plays.
     */
    @Test
    fun theDashsTitleIsAlwaysREau() {
        shown(77, "TemplateService")
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = AaConfig(media = true),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                ),
            )
        }
        ProjectionService.start(context)
        await("streaming", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }

        val status = MediaPlayback.MediaPlaybackStatus.newBuilder().setState(MediaPlayback.MediaPlaybackStatus.State.PLAYING)
            .setMediaSource("Spotify").buildPartial().toByteArray()
        androidAuto.send(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_STATUS, status)
        shown(78, "GmmCarProjectionService")
        Thread.sleep(TITLE_WAIT_MS)
        assertTrue("titles at the dash: ${titles().takeLast(8)}", titles().isNotEmpty() && titles().all { it == "REau" })
    }

    private companion object {
        const val TITLE_WAIT_MS = 4_000L
    }
}
