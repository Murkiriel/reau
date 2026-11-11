package com.reau.app.devtools

import com.reau.app.dash.DashSession
import com.reau.app.dash.DashDump
import com.reau.app.dash.nav.DashManeuverGlyphs
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The debug build's arrow test (the 11/11/2026 request: the roteiro's photographs of the dash, parked, with the arrows of
 * [DashManeuverGlyphs.testSequence], which no screen sent): the steps through the sequence, what the screen writes for the photo, and that the dash's
 * session sends the byte, by the path of the real arrow, and gives the real arrow back at the end.
 */
class ArrowTrialTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var log: File

    @Before
    fun setUp() {
        ArrowTrial.stop()
        log = tmp.newFile("ride-arrow.log")
        RideDiagnostics.testOnlySetFile(log)
    }

    @After
    fun tearDown() {
        ArrowTrial.stop()
        scope.cancel()
        RideDiagnostics.testOnlyReset()
    }

    // ── the sequence ──

    @Test
    fun `the sequence has the bytes the field roteiro asks to photograph`() {
        val wanted = listOf(0x07, 0x08, 0x2D, 0x2E, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x29, 0x3C)
        assertTrue("missing: ${wanted.filterNot { it in DashManeuverGlyphs.testSequence }}", DashManeuverGlyphs.testSequence.containsAll(wanted))
        assertEquals("ascending and without repeats", DashManeuverGlyphs.testSequence.sorted().distinct(), DashManeuverGlyphs.testSequence)
    }

    @Test
    fun `the first Next sends the first byte, then the next ones in order, and the last wraps to the first`() {
        val sequence = DashManeuverGlyphs.testSequence
        assertNull(ArrowTrial.byte())
        ArrowTrial.next()
        assertEquals(sequence.first(), ArrowTrial.byte())
        ArrowTrial.next()
        assertEquals(sequence[1], ArrowTrial.byte())
        repeat(sequence.size - 2) { ArrowTrial.next() }
        assertEquals(sequence.last(), ArrowTrial.byte())
        ArrowTrial.next()
        assertEquals("wrapped", sequence.first(), ArrowTrial.byte())
    }

    @Test
    fun `Previous goes back one, the first wraps to the last, and from off it starts at the last`() {
        val sequence = DashManeuverGlyphs.testSequence
        ArrowTrial.previous()
        assertEquals(sequence.last(), ArrowTrial.byte())
        ArrowTrial.previous()
        assertEquals(sequence[sequence.size - 2], ArrowTrial.byte())
        ArrowTrial.next()
        assertEquals(sequence.last(), ArrowTrial.byte())
        repeat(sequence.size - 1) { ArrowTrial.previous() }
        assertEquals(sequence.first(), ArrowTrial.byte())
        ArrowTrial.previous()
        assertEquals("wrapped", sequence.last(), ArrowTrial.byte())
    }

    @Test
    fun `Stop gives the real arrow back and the next start begins at the first byte again`() {
        ArrowTrial.next(); ArrowTrial.next()
        ArrowTrial.stop()
        assertNull(ArrowTrial.byte())
        assertNull(ArrowTrial.now.value)
        ArrowTrial.next()
        assertEquals(DashManeuverGlyphs.testSequence.first(), ArrowTrial.byte())
    }

    // ── what the screen writes for the photo ──

    @Test
    fun `the label is the byte in hex and its expected name in both languages`() {
        assertEquals("0x2D  Bear off to the right  (Abrir à direita)", ArrowTrial.label(0x2D))
        assertEquals("0x3C  Destination pin alone  (Só o marcador do destino)", ArrowTrial.label(0x3C))
    }

    @Test
    fun `every byte of the sequence gets a label, and the ones with no name say to write down what is drawn`() {
        for (code in DashManeuverGlyphs.testSequence) {
            val label = ArrowTrial.label(code)
            assertTrue(label, label.startsWith("0x%02X  ".format(code)))
        }
        assertTrue(ArrowTrial.label(0x41), "no name yet" in ArrowTrial.label(0x41)) // an empty placeholder bubble
        assertTrue(ArrowTrial.label(DashManeuverGlyphs.HIDDEN_BUBBLE), "hides the bubble" in ArrowTrial.label(DashManeuverGlyphs.HIDDEN_BUBBLE))
    }

    @Test
    fun `each step and the end are written in the ride log, for the notes of the photo`() {
        ArrowTrial.next()
        ArrowTrial.stop()
        RideDiagnostics.logAndWait("ui", "end")
        val lines = log.readLines().filter { "[debug]" in it }
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], "arrow test: 0x00" in lines[0] && "debug build only" in lines[0])
        assertTrue(lines[1], "arrow test ended" in lines[1])
    }

    // ── only with the dash connected ──

    @Test
    fun `the test is offered only with a projection running and the dash connected`() {
        for (phase in Phase.entries) {
            for (dash in DashLink.entries) {
                assertEquals("$phase $dash", phase == Phase.RUNNING && dash == DashLink.READY, DebugTriggers.arrowTestAvailable(phase, dash))
            }
        }
    }

    // ── what the dash's session sends ──

    private fun bubbleOf(packet: ByteArray): List<Int> =
        DashDump.outgoingTlvs(packet).single { it.type == 0x05 && it.sub == 0x02 }.value.map { it.toInt() and 0xFF }

    @Test
    fun `with no guidance and no test the session sends no nav packet, with the test it sends the byte in the bubble`() {
        val session = DashSession(scope)
        assertNull(session.navPacketNow())
        ArrowTrial.next()
        val packet = session.navPacketNow()
        assertNotNull(packet)
        assertEquals(listOf(DashManeuverGlyphs.testSequence.first()), bubbleOf(packet!!))
        ArrowTrial.stop()
        assertNull("the real state again: no guidance, no packet", session.navPacketNow())
    }

    @Test
    fun `every byte of the sequence reaches the bubble as it is, even the ones the real arrow never sends`() {
        val session = DashSession(scope)
        for (code in DashManeuverGlyphs.testSequence) {
            ArrowTrial.next()
            assertEquals(code, ArrowTrial.byte())
            assertEquals("0x%02X".format(code), listOf(code), bubbleOf(session.navPacketNow()!!))
        }
    }

    @Test
    fun `the packet is the real one but for the bubble's byte, and the real arrow comes back after the test`() {
        val session = DashSession(scope)
        session.updateNavInfo(DashManeuverGlyphs.TURN_LEFT, 500, DashCommands.NAV_UNIT_METERS, 12, DashCommands.NAV_UNIT_KM_TENTHS, "08:15", "Rua X")
        val real = session.navPacketNow()!!
        assertEquals(listOf(DashManeuverGlyphs.TURN_LEFT), bubbleOf(real))

        ArrowTrial.next()
        val during = session.navPacketNow()!!
        assertEquals(listOf(DashManeuverGlyphs.testSequence.first()), bubbleOf(during))
        val differing = real.indices.filter { real[it] != during[it] }
        assertTrue("same size, other bytes differ only where the bubble is: $differing", real.size == during.size && differing.size == 1)

        ArrowTrial.stop()
        // The seq byte of a packet is patched on the wire; this one is not sent, so the two are equal as built.
        assertArrayEquals("the real arrow is back, byte for byte", real, session.navPacketNow())
    }

    @Test
    fun `the route card carries the test's byte while it is on and is the real card before and after`() {
        val session = DashSession(scope)
        session.updateNavInfo(DashManeuverGlyphs.TURN_RIGHT, 300, DashCommands.NAV_UNIT_METERS, 5, DashCommands.NAV_UNIT_KM_TENTHS, "09:00", "")
        val real = session.routeCardNow()
        ArrowTrial.next()
        assertTrue("the card changes during the test", !real.contentEquals(session.routeCardNow()))
        ArrowTrial.stop()
        assertArrayEquals(real, session.routeCardNow())
    }

    @Test
    fun `the test without guidance puts the arrow on the idle card's place too`() {
        val session = DashSession(scope)
        ArrowTrial.next()
        val card = session.routeCardNow()
        ArrowTrial.stop()
        assertFalse("not the idle card of the pin", card.contentEquals(session.routeCardNow()))
    }
}
