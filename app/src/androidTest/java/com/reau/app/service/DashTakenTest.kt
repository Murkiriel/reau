package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.card.CardPainter
import com.reau.app.dash.DashPorts
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Another app holding the dash: the test binds the phone's UDP 2002 with address reuse on, exactly as another app
 * on the dash's port does, and switches the projection on (the card mode, so no Android Auto is needed). The app
 * must refuse to connect, say why, and connect by itself once the port is let go.
 */
@RunWith(AndroidJUnit4::class)
class DashTakenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalCardSides = ProjectionService.cardSidesFactory
    private val dash = FakeDashPeer().apply { autoAck = true }
    private var otherApp: DatagramSocket? = null
    @Volatile private var linkUps = 0

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) {
            linkUps++
            onReady("DashFake", null)
        }
        override fun stop() = Unit
    }

    private fun holdTheDashPortLikeAnotherApp() {
        otherApp = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(DashSocket.RX_PORT))
        }
    }

    private fun startCard() {
        ProjectionService.cardSidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                    card = { CardPainter.legibility() },
                ),
            )
        }
        ProjectionService.startCard(context)
    }

    @After
    fun tearDown() {
        otherApp?.close()
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the service gone", 10_000) { !serviceRunning() }
        dash.close()
        ProjectionService.cardSidesFactory = originalCardSides
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$what (projection: ${ProjectionService.projection.value}, frames at the dash: ${dash.frameArrivals.size})", condition())
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun serviceRunning(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == ProjectionService::class.java.name }

    @Test
    fun withTheDashPortHeldByAnotherAppTheAppRefusesAndSaysSo() {
        holdTheDashPortLikeAnotherApp()
        assertTrue("the check sees it", !DashPorts.isFree(DashPorts.DASH_RX_PORT))

        startCard()
        await("the dash seen as taken", 10_000) { ProjectionService.projection.value.dash == DashLink.TAKEN }
        assertEquals(Headline.DASH_TAKEN, ProjectionService.projection.value.headline)

        Thread.sleep(3_000)
        assertEquals("nothing sent to the dash meanwhile", 0, dash.frameArrivals.size)
        assertEquals("not even the bike's Wi-Fi asked for", 0, linkUps)
        assertTrue("still taken after a retry", ProjectionService.projection.value.dash in setOf(DashLink.TAKEN, DashLink.CONNECTING))
    }

    @Test
    fun onceTheOtherAppLetsGoTheProjectionConnectsByItself() {
        holdTheDashPortLikeAnotherApp()
        startCard()
        await("the dash seen as taken", 10_000) { ProjectionService.projection.value.dash == DashLink.TAKEN }

        otherApp!!.close()
        await("the card at the dash", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
        assertEquals(Headline.CARD, ProjectionService.projection.value.headline)
    }

    @Test
    fun withThePortFreeNothingChanges() {
        startCard()
        await("the card at the dash", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
        assertEquals(0, ProjectionService.projection.value.dashRetries)
    }
}
