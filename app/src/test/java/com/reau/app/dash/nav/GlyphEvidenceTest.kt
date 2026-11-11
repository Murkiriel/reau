package com.reau.app.dash.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What stands behind each byte the app sends to the dash's maneuver bubble. The other tests of the glyphs lock the bytes; this one says, for
 * each byte, whether its drawing was ever looked at on REau's own dash or is known only from the published scan the table comes from, so a
 * green test is not read as "seen on the bike". A byte the app starts to send needs its line here, and its place on the debug card.
 */
class GlyphEvidenceTest {
    private enum class Evidence {
        /** The drawing was looked at on REau's own dash by its rider. */
        SEEN_ON_REAUS_DASH,

        /** Only locked: the drawing is known from the scan alone, and the byte is on the debug card to be looked at. */
        SCAN_ONLY,
    }

    // Typed out by hand, byte by byte, from what is recorded of the rides: the rider saw the roundabout turning the wrong way and it was put
    // right (counter-clockwise, driving on the right). Nothing else was reported as seen.
    private val evidence: Map<Int, Evidence> = buildMap {
        for (b in 0x0A..0x13) put(b, Evidence.SEEN_ON_REAUS_DASH) // roundabout, counter-clockwise, exits 0 to 9: the direction was seen
        for (b in 0x50..0x59) put(b, Evidence.SCAN_ONLY)          // the same, exits 10 to 19
        for (b in 0x31..0x3A) put(b, Evidence.SCAN_ONLY)          // roundabout, clockwise
        for (b in 0x46..0x4F) put(b, Evidence.SCAN_ONLY)
        for (b in listOf(0x00, 0x01, 0x02)) put(b, Evidence.SCAN_ONLY) // arrive
        for (b in listOf(0x03, 0x04)) put(b, Evidence.SCAN_ONLY)       // merge
        for (b in listOf(0x05, 0x06)) put(b, Evidence.SCAN_ONLY)       // fork
        for (b in listOf(0x07, 0x08)) put(b, Evidence.SCAN_ONLY)       // the exit ramp
        put(0x09, Evidence.SCAN_ONLY)                                  // straight
        for (b in 0x14..0x19) put(b, Evidence.SCAN_ONLY)               // turns
        for (b in listOf(0x1A, 0x3D)) put(b, Evidence.SCAN_ONLY)       // U-turns
        put(0x1C, Evidence.SCAN_ONLY)                                  // recalculating
        for (b in listOf(0x3E, 0x3F)) put(b, Evidence.SCAN_ONLY)       // ferry, level crossing
        put(0x42, Evidence.SCAN_ONLY)                                  // lost signal
    }

    /** Every byte the app can send for a maneuver: each Android Auto maneuver number with each exit number, rerouting or not, with and without a network. */
    private fun sent(): Set<Int> = buildSet {
        for (type in -1..80) for (exit in 0..25) add(AaManeuvers.glyphFor(type, exit))
        add(AaManeuvers.glyphFor(1, 0, rerouting = true))
        add(DashManeuverGlyphs.SIGNAL_LOST)
        for (type in ManeuverType.entries) for (side in DrivingSide.entries) for (exit in 0..25) {
            add(DashManeuverGlyphs.codeFor(type, roundaboutExit = exit, drivingSide = side))
            add(DashManeuverGlyphs.codeFor(type, roundaboutExit = exit, uTurnRight = true, drivingSide = side))
        }
        remove(DashManeuverGlyphs.HIDDEN_BUBBLE)
    }

    @Test
    fun `every byte the app sends has its evidence written down, and no line is left for a byte it does not send`() {
        val sent = sent()
        assertEquals("sent without a line of evidence", emptyList<String>(), (sent - evidence.keys).sorted().map { "0x%02X".format(it) })
        assertEquals("a line of evidence for a byte never sent", emptyList<String>(), (evidence.keys - sent).sorted().map { "0x%02X".format(it) })
    }

    @Test
    fun `only the roundabout was seen on REau's own dash`() {
        val seen = evidence.filterValues { it == Evidence.SEEN_ON_REAUS_DASH }.keys
        assertEquals((0x0A..0x13).toSet(), seen)
        for (b in seen) assertTrue(DashManeuverGlyphs.describe(b)!!.contains("counter-clockwise", ignoreCase = true))
    }

    @Test
    fun `every drawing known from the scan alone is on the debug card, the roundabouts' other exit numbers aside`() {
        val roundabouts = (0x0A..0x13) + (0x50..0x59) + (0x31..0x3A) + (0x46..0x4F)
        val toLookAt = evidence.filterValues { it == Evidence.SCAN_ONLY }.keys - roundabouts.toSet()
        val card = DashManeuverGlyphs.testSequence.toSet()
        assertEquals("not on the debug card", emptyList<String>(), (toLookAt - card).sorted().map { "0x%02X".format(it) })
        assertTrue("a clockwise roundabout and a two-digit exit are there too", 0x31 in card && 0x50 in card)
    }
}
