package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.Channel
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.FakeAndroidAutoVideo
import com.reau.app.aa.FakeNavigation
import com.reau.app.aa.ServiceDiscoveryResponse
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.reau.app.aa.proto.NavigationStatus.NextTurnDetail.NextEvent
import com.reau.app.aa.proto.NavigationStatus.NextTurnDetail.Side
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.dash.protocol.Tlv
import com.reau.app.video.TestH264Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * Maneuver data to the dash, behind the switch, inside the service: the fake Android Auto sends a
 * sequence of maneuvers on the navigation status channel and the fake dash must get the right nav packets; with the
 * switch off, the service discovery response has no navigation channel and the dash gets no maneuver at all.
 */
@RunWith(AndroidJUnit4::class)
class ManeuverEndToEndTest {
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
        assertTrue("$what (projection: ${ProjectionService.projection.value}, maneuvers at the dash: ${maneuvers()})", condition())
    }

    private fun startStreaming(navigation: Boolean) {
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = AaConfig(navigation = navigation),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                ),
            )
        }
        ProjectionService.start(context)
        await("streaming", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
    }

    private fun send(message: Pair<Int, ByteArray>) = androidAuto.send(Channel.ID_NAV, message.first, message.second)

    /** Every nav packet the dash got: its glyph TLV (05 02) and the four that follow it. */
    private fun navPackets(): List<List<Tlv>> {
        val all = dash.receivedSinceHandshake()
        return all.indices.filter { all[it].type == 0x05 && all[it].sub == 0x02 && it + 4 < all.size && all[it + 1].sub == 0x04 }
            .map { all.subList(it, it + 5) }
    }

    /** The glyph bytes of the dash's maneuver bubble, in every packet that carries one (nav packets and route cards). */
    private fun maneuvers(): List<Int> =
        dash.receivedSinceHandshake().filter { it.type == 0x05 && it.sub == 0x02 }.map { it.value.single().toInt() and 0xFF }

    private fun u16(tlv: Tlv) = ((tlv.value[0].toInt() and 0xFF) shl 8) or (tlv.value[1].toInt() and 0xFF)

    @Test
    fun aSequenceOfManeuversReachesTheDashAsItsOwnNavPackets() {
        startStreaming(navigation = true)

        send(FakeNavigation.status(active = true))
        send(FakeNavigation.state(NavigationType.TURN_NORMAL_LEFT, road = "Rua Augusta"))
        send(FakeNavigation.position(toManeuverM = 250, toDestinationM = 3_200))
        await("turn left, 250 m, 3.2 km at the dash", 10_000) {
            navPackets().any { p -> p[0].value.single() == 0x14.toByte() && u16(p[1]) == 250 && u16(p[3]) == 32 }
        }
        val turn = navPackets().last { it[0].value.single() == 0x14.toByte() }
        assertEquals("metres", 0x30, turn[2].value.single().toInt() and 0xFF)
        assertEquals("tenths of a kilometre", 0x10, turn[4].value.single().toInt() and 0xFF)

        send(FakeNavigation.state(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW, exit = 2))
        await("roundabout, exit 2", 10_000) { navPackets().any { it[0].value.single() == 0x0C.toByte() } }

        send(FakeNavigation.rerouting())
        await("rerouting: the spinning compass", 10_000) { navPackets().any { it[0].value.single() == 0x1C.toByte() } }

        send(FakeNavigation.status(active = false))
        Thread.sleep(1_500)
        val mark = maneuvers().size
        Thread.sleep(3_000)
        val after = maneuvers().drop(mark)
        assertTrue("route cards still go out", after.isNotEmpty())
        assertTrue("with the guidance over, only the card with no route (the arrival pin): $after", after.all { it == IDLE })
    }

    /** Written after the code, for the mutation run: a turn must never outlive its Android Auto session. */
    @Test
    fun androidAutoGoingAwayInTheMiddleOfAGuidanceHidesTheBubble() {
        startStreaming(navigation = true)
        send(FakeNavigation.status(active = true))
        send(FakeNavigation.state(NavigationType.TURN_NORMAL_RIGHT))
        send(FakeNavigation.position(toManeuverM = 400, toDestinationM = 900))
        await("turn right at the dash", 10_000) { navPackets().any { it[0].value.single() == 0x15.toByte() } }

        video.close()
        androidAuto.close()
        await("Android Auto seen as gone", 10_000) { ProjectionService.projection.value.androidAuto != AaLinkState.ACTIVE }
        Thread.sleep(1_500)
        val mark = maneuvers().size
        Thread.sleep(3_000)
        val after = maneuvers().drop(mark)
        assertTrue("route cards still go out", after.isNotEmpty())
        assertTrue("the last turn is not left on the dash: $after", after.all { it == IDLE })
    }

    @Test
    fun withTheSwitchOffNoNavigationChannelIsAnnouncedAndTheDashGetsNoManeuver() {
        startStreaming(navigation = false)
        assertEquals(AaLinkState.ACTIVE, ProjectionService.projection.value.androidAuto)

        val request = Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build()
        androidAuto.send(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE, request.toByteArray())
        var answer: com.reau.app.aa.FakeAndroidAutoPeer.Received? = null
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (answer == null && SystemClock.elapsedRealtime() < deadline) {
            val m = androidAuto.receive(1_000) ?: continue
            if (m.channel == Channel.ID_CTR && m.type == Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE) answer = m
        }
        assertNotNull("a service discovery response", answer)
        val expected = ServiceDiscoveryResponse(AaConfig()).parse(Control.ServiceDiscoveryResponse.newBuilder()).build()
        assertEquals("the response of before the navigation switch", expected, Control.ServiceDiscoveryResponse.parseFrom(answer!!.body))

        // Before streaming, the session sends route cards with its template's glyph (0x3C):
        // that is not the maneuver data's, so only what comes after the guidance is looked at.
        val mark = maneuvers().size
        send(FakeNavigation.status(active = true))
        send(FakeNavigation.state(NavigationType.TURN_NORMAL_LEFT))
        send(FakeNavigation.position(toManeuverM = 250, toDestinationM = 3_200))
        Thread.sleep(3_000)
        val after = maneuvers().drop(mark)
        assertTrue("no nav packet: ${navPackets().size}", navPackets().isEmpty())
        assertTrue("route cards still go out", after.isNotEmpty())
        assertTrue("only the card with no route: $after", after.all { it == IDLE })
    }

    /**
     * The ring vanished on a stretch with no maneuver and the joystick stopped: Waze speaks the
     * old dialect and sends `event=UNKNOWN` every 2-3 s on such a stretch (163 times mid-route in the ride logs).
     * The ring keeps the last turn through them and through the silence after; the hidden bubble (0xFF) never goes out.
     */
    @Test
    fun wazesUnknownManeuverOnAStretchKeepsTheRing() {
        startStreaming(navigation = true)
        send(FakeNavigation.status(active = true))
        send(FakeNavigation.nextTurn(NextEvent.TURN, Side.LEFT))
        send(FakeNavigation.position(toManeuverM = 300, toDestinationM = 5_000))
        await("turn left at the dash", 10_000) { navPackets().any { it[0].value.single() == 0x14.toByte() } }

        val mark = maneuvers().size
        repeat(3) {
            send(FakeNavigation.nextTurn(NextEvent.UNKNOWN))
            Thread.sleep(700)
        }
        Thread.sleep(3_000) // and Waze silent for a while
        val after = maneuvers().drop(mark)
        assertTrue("the ring still goes out: $after", after.size >= 3)
        assertTrue("never the hidden bubble while guiding: $after", after.none { it == HIDDEN })
        assertTrue("the last turn kept: $after", after.all { it == 0x14 })
    }

    private companion object {
        /** The code that hides the ring, and with it the map-mode joystick. */
        const val HIDDEN = com.reau.app.dash.protocol.DashCommands.NAV_MANEUVER_NONE

        /** The card with no route carries the arrival pin by default, so the dash keeps its map-mode joystick. */
        const val IDLE = com.reau.app.dash.protocol.DashCommands.NAV_MANEUVER_ARRIVAL_PIN
    }
}
