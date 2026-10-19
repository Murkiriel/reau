package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.protocol.Tlv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What is this app's own in the dash session, against [FakeDashPeer]: the name it announces to the dash, the
 * route-card title the dash needs to keep its decoder open (there is no destination here: the picture is Waze's),
 * and the projection keep-alive cadence, which had no test before.
 *
 * As with [DashSessionFakeDashTest], this proves sequence and wiring against the fake, not that a real dash
 * accepts it.
 */
@RunWith(AndroidJUnit4::class)
class DashSessionREauTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
    }

    private fun connected(): Pair<DashSession, FakeDashPeer> {
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) })
            .also { session = it }
        s.connect("DashFake")
        val deadline = System.currentTimeMillis() + 8_000
        while (s.state.value != DashState.READY && s.state.value != DashState.ERROR && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(DashState.READY, s.state.value)
        runBlocking { delay(300) } // the fake's receiver thread may still be draining the last sends
        return s to fake
    }

    /** A NUL-terminated text TLV, as the dash reads it. */
    private fun Tlv.text(): String = String(value, Charsets.UTF_8).trimEnd('\u0000')

    @Test
    fun theDashIsToldItIsConnectedToREau() {
        val (_, fake) = connected()

        val names = fake.received.filter { it.type == 0x06 && it.sub == 0x0B }.map { it.text() }

        assertTrue("the hostname announce reached the dash", names.isNotEmpty())
        assertEquals(setOf("REau"), names.toSet())
    }

    /** REau is the app really working, so the card says REau, not Waze. */
    @Test
    fun withNoAppKnownTheRouteCardSaysREau() {
        val (_, fake) = connected()

        val titles = fake.receivedSinceHandshake().filter { it.type == 0x05 && it.sub == 0x01 }.map { it.text() }

        assertEquals("the four route cards and the confirmation one", 5, titles.size)
        assertEquals(setOf("REau"), titles.toSet())
    }

    /** The app Android Auto shows goes in the card repeated every second, from the next one. */
    @Test
    fun aBlankRouteCardTitleFallsBackToREau() {
        val (s, fake) = connected()
        val before = fake.receivedSinceHandshake().count { it.type == 0x05 && it.sub == 0x01 }

        // Each call sends its card from a coroutine that reads the title when it runs: give the first one time
        // to leave before changing the title again.
        s.updateRouteCard("Casa")
        runBlocking { delay(400) }
        s.updateRouteCard("   ")
        runBlocking { delay(400) }

        val titles = fake.receivedSinceHandshake().filter { it.type == 0x05 && it.sub == 0x01 }.drop(before).map { it.text() }
        assertEquals(listOf("Casa", "REau"), titles)
    }

    /**
     * The zoom state with both icons (06 0C = 30), so the dash's ZOOM shows "-" and "+", goes once the nav mode opens,
     * after the start of navigation, and again after each zoom or recentre press' echo; other presses get the echo
     * only.
     */
    @Test
    fun theZoomIconsGoWhenTheNavModeOpensAndAfterEachZoomPress() {
        val (_, fake) = connected()
        fun zooms() = fake.receivedSinceHandshake().filter { it.type == 0x06 && it.sub == 0x0C }
        assertEquals(listOf(0x30), zooms().map { it.value.single().toInt() and 0xFF })
        val order = fake.receivedSinceHandshake().map { it.type to it.sub }
        assertTrue("after the start (06 80 = 0B): $order", order.indexOf(0x06 to 0x0C) > order.indexOf(0x06 to 0x80))

        fake.pressButton(0x05)
        runBlocking { delay(400) }
        assertEquals("a media press: the echo only", 1, zooms().size)
        for (code in listOf(0x13, 0x14, 0x15)) fake.pressButton(code)
        runBlocking { delay(400) }
        val tail = fake.receivedSinceHandshake().map { it.type to it.sub }.filter { it == (0x06 to 0x80) || it == (0x06 to 0x0C) }.takeLast(6)
        assertEquals(List(3) { listOf(0x06 to 0x80, 0x06 to 0x0C) }.flatten(), tail)
        assertTrue(zooms().all { it.value.single().toInt() and 0xFF == 0x30 })
    }

    @Test
    fun theProjectionKeepAliveRepeatsAtAboutFourHertzWhileStreaming() {
        val (s, fake) = connected()
        fun projectionFrames() = fake.receivedSinceHandshake().count {
            it.type == 0x05 && it.sub == 0x56 && it.value.firstOrNull() == 0x55.toByte()
        }

        s.startStreaming()
        runBlocking { delay(500) }
        val start = projectionFrames()
        runBlocking { delay(4_000) }
        val inFourSeconds = projectionFrames() - start

        // 4 Hz for 4 s is 16; without it the dash sleeps. Wide enough for a loaded emulator, narrow enough to
        // tell 4 Hz from the 1 Hz of the other keep-alives and from a loop with no pause.
        assertTrue("projection frames in 4 s: $inFourSeconds", inFourSeconds in 12..20)
    }

    /**
     * With no route the card sent every second carries the arrival pin, so the dash keeps passing its
     * joystick on in its map mode; the hidden bubble (0xFF) is only there when the rider picks it, at once.
     */
    @Test
    fun withNoRouteTheCardEverySecondCarriesTheArrivalPinUnlessTheRiderHidesIt() {
        val (s, fake) = connected()
        fun glyphs() = fake.receivedSinceHandshake().filter { it.type == 0x05 && it.sub == 0x02 }.map { it.value.single().toInt() and 0xFF }
        s.startStreaming()
        runBlocking { delay(2_500) }
        assertTrue("glyphs: ${glyphs()}", glyphs().isNotEmpty() && glyphs().all { it == 0x3C })

        val before = glyphs().size
        s.idleCard = DashIdleCard.HIDDEN
        runBlocking { delay(2_500) }
        val after = glyphs().drop(before)
        assertTrue("glyphs after hiding: $after", after.isNotEmpty() && after.last() == 0xFF)
    }

    /**
     * Hiding the maneuver ring in the dash's media mode never worked on the dash: the ring's glyph goes in
     * the dash's media mode as in its map mode.
     */
    @Test
    fun theRingStaysInBothModesOfTheDash() {
        val (s, fake) = connected()
        // No video here: the dash talks, as a real one does, so the 4 s watchdog keeps the session.
        scope.launch { while (isActive) { fake.ackPFrame(); delay(500) } }
        fun glyphs(mark: Int) = fake.received.drop(mark).filter { it.type == 0x05 && it.sub == 0x02 }.map { it.value.single().toInt() and 0xFF }
        fun after(ms: Long): Int = fake.received.size.also { runBlocking { delay(ms) } }
        s.startStreaming()
        runBlocking { delay(1_500) }
        fake.pressButton(0x18) // IN: the media mode
        var mark = after(1_500)
        assertEquals("media mode", 0x3C, glyphs(mark).last())
        fake.pressButton(0x13) // the map mode's right
        mark = after(1_500)
        assertEquals("map mode", 0x3C, glyphs(mark).last())
        // Guiding: the maneuver, in the media mode too.
        val turn = com.reau.app.dash.nav.DashManeuverGlyphs.STRAIGHT
        val meters = com.reau.app.dash.protocol.DashCommands.NAV_UNIT_METERS
        s.updateNavInfo(turn, 200, meters, 3, meters)
        fake.pressButton(0x18)
        mark = after(1_500)
        assertEquals("guiding, media mode", turn, glyphs(mark).last())
        assertEquals(DashState.STREAMING, s.state.value)
    }
}
