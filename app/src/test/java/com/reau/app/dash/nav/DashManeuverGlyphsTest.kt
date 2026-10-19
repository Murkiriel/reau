package com.reau.app.dash.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte the dash's nav bubble is told to draw for each maneuver. The expected values below are typed out by hand
 * from the glyph table observed on the dash (every byte 0x00..0xFF sent to a real K1G dash and checked by eye), NOT
 * computed with the same formulas as the code under test.
 */
class DashManeuverGlyphsTest {

    // ── the plain maneuvers ─────────────────────────────────────────────────────

    @Test
    fun `every maneuver type maps to its catalog byte`() {
        val expected = mapOf(
            ManeuverType.CONTINUE to 0x09,       // straight arrow (NOT 0x0B: that one draws roundabout exit 1)
            ManeuverType.DEPART to 0x09,
            ManeuverType.TURN_LEFT to 0x14,
            ManeuverType.TURN_RIGHT to 0x15,
            ManeuverType.SLIGHT_LEFT to 0x18,
            ManeuverType.SLIGHT_RIGHT to 0x19,
            ManeuverType.SHARP_LEFT to 0x16,
            ManeuverType.SHARP_RIGHT to 0x17,
            ManeuverType.UTURN to 0x3D,          // no side given, right-hand traffic: the U-turn is made via the left
            ManeuverType.ROUNDABOUT to 0x0C,     // exit 2, counter-clockwise (right-hand traffic)
            ManeuverType.ARRIVE to 0x00,
            ManeuverType.ARRIVE_LEFT to 0x01,
            ManeuverType.ARRIVE_RIGHT to 0x02,
            ManeuverType.MERGE_LEFT to 0x04,     // the rider moves left = his road joins from the RIGHT (see the merge test below)
            ManeuverType.MERGE_RIGHT to 0x03,
            ManeuverType.KEEP_LEFT to 0x06,      // Y-fork, left leg
            ManeuverType.KEEP_RIGHT to 0x05,     // Y-fork, right leg
        )
        assertEquals("a new ManeuverType needs a decision here", ManeuverType.entries.toSet(), expected.keys)
        for ((type, code) in expected) {
            assertEquals(type.name, code, DashManeuverGlyphs.codeFor(type, roundaboutExit = 2))
        }
    }

    @Test
    fun `left and right of the same maneuver are different bytes`() {
        val pairs = listOf(
            ManeuverType.TURN_LEFT to ManeuverType.TURN_RIGHT,
            ManeuverType.SLIGHT_LEFT to ManeuverType.SLIGHT_RIGHT,
            ManeuverType.SHARP_LEFT to ManeuverType.SHARP_RIGHT,
            ManeuverType.KEEP_LEFT to ManeuverType.KEEP_RIGHT,
            ManeuverType.ARRIVE_LEFT to ManeuverType.ARRIVE_RIGHT,
            ManeuverType.MERGE_LEFT to ManeuverType.MERGE_RIGHT,
        )
        for ((l, r) in pairs) assertTrue("$l vs $r", DashManeuverGlyphs.codeFor(l) != DashManeuverGlyphs.codeFor(r))
    }

    @Test
    fun `the old placeholder 0x0B is never sent for a maneuver`() {
        for (type in ManeuverType.entries) {
            val code = DashManeuverGlyphs.codeFor(type, roundaboutExit = null)
            if (type != ManeuverType.ROUNDABOUT) assertTrue("$type must not draw a roundabout", code != 0x0B)
        }
        assertEquals("only a roundabout with exit 1 is 0x0B", 0x0B, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = 1))
    }

    @Test
    fun `a merge draws the Y whose road joins from the side the rider comes from, the opposite of the way he moves`() {
        // 0x03 = "your road joins from the LEFT" (you drift right), 0x04 = "joins from the RIGHT" (you drift left).
        assertEquals(0x03, DashManeuverGlyphs.codeFor(ManeuverType.MERGE_RIGHT))
        assertEquals(0x04, DashManeuverGlyphs.codeFor(ManeuverType.MERGE_LEFT))
    }

    // ── roundabouts ─────────────────────────────────────────────────────────────

    private val ccw = listOf(
        0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13,      // exits 0..9
        0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,      // exits 10..19
    )
    private val cw = listOf(
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A,      // exits 0..9
        0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F,      // exits 10..19
    )

    @Test
    fun `every roundabout exit has its own byte, counter-clockwise`() {
        for (exit in 0..19) assertEquals("exit $exit", ccw[exit], DashManeuverGlyphs.roundabout(exit, clockwise = false))
    }

    @Test
    fun `every roundabout exit has its own byte, clockwise`() {
        for (exit in 0..19) assertEquals("exit $exit", cw[exit], DashManeuverGlyphs.roundabout(exit, clockwise = true))
    }

    @Test
    fun `the 40 roundabout bytes are all different`() {
        assertEquals(40, (ccw + cw).toSet().size)
    }

    @Test
    fun `a roundabout exit beyond the catalog is clamped, a missing exit is the plain circle`() {
        assertEquals(0x59, DashManeuverGlyphs.roundabout(20, clockwise = false))
        assertEquals(0x59, DashManeuverGlyphs.roundabout(999, clockwise = false))
        assertEquals(0x4F, DashManeuverGlyphs.roundabout(25, clockwise = true))
        assertEquals(0x0A, DashManeuverGlyphs.roundabout(-3, clockwise = false))
        assertEquals(0x0A, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = null))
    }

    @Test
    fun `the exit number of the route is the number in the circle`() {
        assertEquals(0x0B, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = 1))
        assertEquals(0x0D, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = 3))
        assertEquals(0x52, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = 12))
    }

    // ── driving side and U-turns ────────────────────────────────────────────────

    @Test
    fun `right-hand traffic circulates counter-clockwise, left-hand clockwise`() {
        assertFalse(DrivingSide.RIGHT_HAND.roundaboutClockwise)
        assertTrue(DrivingSide.LEFT_HAND.roundaboutClockwise)
        assertEquals(0x0C, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = 2, drivingSide = DrivingSide.RIGHT_HAND))
        assertEquals(0x33, DashManeuverGlyphs.codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = 2, drivingSide = DrivingSide.LEFT_HAND))
    }

    @Test
    fun `a U-turn goes via the left in right-hand traffic and via the right in left-hand traffic, unless the router says which`() {
        assertEquals(0x3D, DashManeuverGlyphs.codeFor(ManeuverType.UTURN, drivingSide = DrivingSide.RIGHT_HAND))
        assertEquals(0x1A, DashManeuverGlyphs.codeFor(ManeuverType.UTURN, drivingSide = DrivingSide.LEFT_HAND))
        assertEquals(0x1A, DashManeuverGlyphs.codeFor(ManeuverType.UTURN, uTurnRight = true, drivingSide = DrivingSide.RIGHT_HAND))
        assertEquals(0x3D, DashManeuverGlyphs.codeFor(ManeuverType.UTURN, uTurnRight = false, drivingSide = DrivingSide.LEFT_HAND))
    }

    @Test
    fun `the driving side is looked up from a country code`() {
        assertEquals(DrivingSide.RIGHT_HAND, DrivingSide.forCountry("BR"))
        assertEquals(DrivingSide.RIGHT_HAND, DrivingSide.forCountry("us"))
        assertEquals(DrivingSide.LEFT_HAND, DrivingSide.forCountry("IN"))
        assertEquals(DrivingSide.LEFT_HAND, DrivingSide.forCountry("gb"))
        assertEquals(DrivingSide.LEFT_HAND, DrivingSide.forCountry("JP"))
        assertEquals(DrivingSide.LEFT_HAND, DrivingSide.forCountry("AU"))
        assertEquals("unknown or missing country: the global default", DrivingSide.RIGHT_HAND, DrivingSide.forCountry(null))
        assertEquals(DrivingSide.RIGHT_HAND, DrivingSide.forCountry(""))
        assertEquals(DrivingSide.RIGHT_HAND, DrivingSide.forCountry("ZZ"))
    }

    // ── the catalog itself ──────────────────────────────────────────────────────

    @Test
    fun `bytes 0x00 to 0x59 draw a bubble, six of them an empty one, and everything above is hidden`() {
        val empty = listOf(0x2B, 0x2C, 0x41, 0x43, 0x44, 0x45)
        assertEquals(empty.toSet(), DashManeuverGlyphs.EMPTY_BUBBLES)
        for (b in 0x00..0x59) {
            assertEquals("0x%02X".format(b), b !in empty, DashManeuverGlyphs.isVisible(b))
            assertEquals("0x%02X".format(b), b !in empty, DashManeuverGlyphs.describe(b) != null)
        }
        for (b in listOf(0x5A, 0x60, 0x7F, 0x80, 0xFE, 0xFF)) {
            assertFalse("0x%02X".format(b), DashManeuverGlyphs.isVisible(b))
            assertNull(DashManeuverGlyphs.describe(b))
        }
        assertFalse(DashManeuverGlyphs.isVisible(-1))
        assertEquals(84, (0x00..0x59).count { DashManeuverGlyphs.isVisible(it) })
    }

    @Test
    fun `the descriptions say what the bubble draws`() {
        assertTrue(DashManeuverGlyphs.describe(0x09)!!.contains("straight", ignoreCase = true))
        assertTrue(DashManeuverGlyphs.describe(0x14)!!.contains("left", ignoreCase = true))
        assertTrue(DashManeuverGlyphs.describe(0x15)!!.contains("right", ignoreCase = true))
        assertTrue(DashManeuverGlyphs.describe(0x0B)!!.contains("roundabout", ignoreCase = true))
        assertTrue(DashManeuverGlyphs.describe(0x0B)!!.contains("1"))
        assertTrue(DashManeuverGlyphs.describe(0x1C)!!.contains("recalculating", ignoreCase = true))
        assertTrue(DashManeuverGlyphs.describe(0x3D)!!.contains("u-turn", ignoreCase = true))
        assertTrue(DashManeuverGlyphs.describe(0x00)!!.contains("arriv", ignoreCase = true))
    }

    @Test
    fun `whatever the maneuver, the byte is one the dash draws`() {
        for (type in ManeuverType.entries) for (exit in listOf(null, -1, 0, 1, 5, 19, 20, 100)) {
            for (uTurn in listOf(null, true, false)) for (side in DrivingSide.entries) {
                val code = DashManeuverGlyphs.codeFor(type, exit, uTurn, side)
                assertTrue("$type exit=$exit uTurn=$uTurn $side -> 0x%02X".format(code), DashManeuverGlyphs.isVisible(code))
            }
        }
    }

    @Test
    fun `no maneuver and recalculating have their own bytes`() {
        assertEquals(0xFF, DashManeuverGlyphs.HIDDEN_BUBBLE)
        assertEquals(com.reau.app.dash.protocol.DashCommands.NAV_MANEUVER_NONE, DashManeuverGlyphs.HIDDEN_BUBBLE)
        assertFalse(DashManeuverGlyphs.isVisible(DashManeuverGlyphs.HIDDEN_BUBBLE))
        assertEquals(0x1C, DashManeuverGlyphs.RECALCULATING)
        assertTrue(DashManeuverGlyphs.isVisible(DashManeuverGlyphs.RECALCULATING))
    }

    @Test
    fun `the shortcuts jump to the byte each maneuver of the app is sent as`() {
        val shortcuts = DashManeuverGlyphs.typeShortcuts
        assertEquals("ids are unique", shortcuts.size, shortcuts.map { it.id }.toSet().size)
        assertEquals("Portuguese labels are unique", shortcuts.size, shortcuts.map { it.labelPt }.toSet().size)
        assertTrue("every shortcut has a Portuguese label", shortcuts.all { it.labelPt.isNotBlank() })
        val byLabel = shortcuts.associate { it.id to it.code }
        for (type in ManeuverType.entries) if (type != ManeuverType.ROUNDABOUT) {
            assertEquals(type.name, DashManeuverGlyphs.codeFor(type), byLabel.getValue(type.name))
        }
        assertEquals(0x0B, byLabel.getValue("ROUNDABOUT exit 1"))
        assertEquals(0x0C, byLabel.getValue("ROUNDABOUT exit 2"))
        assertEquals(0x0D, byLabel.getValue("ROUNDABOUT exit 3"))
        assertEquals(0x1A, byLabel.getValue("UTURN via the right"))
        assertEquals(0x1C, byLabel.getValue("Recalculating"))
        assertEquals(0xFF, byLabel.getValue("No maneuver (hidden)"))
        for (s in shortcuts) assertTrue("${s.id} -> 0x%02X".format(s.code), DashManeuverGlyphs.isVisible(s.code) || s.code == DashManeuverGlyphs.HIDDEN_BUBBLE)
    }

    @Test
    fun `every byte with an English name has a Portuguese one, and the Portuguese names say the same thing`() {
        for (b in 0x00..0xFF) {
            assertEquals("0x%02X".format(b), DashManeuverGlyphs.describe(b) == null, DashManeuverGlyphs.describePt(b) == null)
        }
        assertEquals("Continuar em frente", DashManeuverGlyphs.describePt(0x09))
        assertEquals("Virar à esquerda", DashManeuverGlyphs.describePt(0x14))
        assertEquals("Virar à direita", DashManeuverGlyphs.describePt(0x15))
        assertEquals("Rotatória anti-horária, saída 1", DashManeuverGlyphs.describePt(0x0B))
        assertEquals("Rotatória horária, saída 2", DashManeuverGlyphs.describePt(0x33))
        assertEquals("Recalculando a rota (bússola girando)", DashManeuverGlyphs.describePt(0x1C))
        assertEquals("Retorno pela esquerda", DashManeuverGlyphs.describePt(0x3D))
        assertEquals("Retorno pela direita", DashManeuverGlyphs.describePt(0x1A))
        // left/right must agree between the two languages, byte by byte
        for (b in 0x00..0xFF) {
            val en = DashManeuverGlyphs.describe(b) ?: continue
            val pt = DashManeuverGlyphs.describePt(b)!!
            assertEquals("0x%02X: $en / $pt".format(b), en.contains("left", true), pt.contains("esquerda", true))
            assertEquals("0x%02X: $en / $pt".format(b), en.contains("right", true), pt.contains("direita", true))
        }
    }

    // ── the bike test sequence (debug card) ─────────────────────────────────────

    @Test
    fun `the test sequence covers what has to be checked on the bike`() {
        val seq = DashManeuverGlyphs.testSequence
        assertEquals(seq.distinct(), seq)
        assertEquals(seq.sorted(), seq)
        for (must in listOf(0x09, 0x0B, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x3D, 0x1A, 0x05, 0x06, 0x00, 0x01, 0x02, 0x0C, 0x33, 0x1C, 0x03, 0x04, 0xFF)) {
            assertTrue("0x%02X missing".format(must), must in seq)
        }
        for (b in seq) assertTrue("0x%02X".format(b), DashManeuverGlyphs.isVisible(b) || b == DashManeuverGlyphs.HIDDEN_BUBBLE)
    }

    @Test
    fun `next candidate steps through the sequence and wraps around`() {
        val seq = DashManeuverGlyphs.testSequence
        assertEquals(seq[1], DashManeuverGlyphs.nextCandidate(seq.first()))
        assertEquals(seq.first(), DashManeuverGlyphs.nextCandidate(seq.last()))
        assertEquals(seq.first(), DashManeuverGlyphs.nextCandidate(0xFF))
        val between = seq.zipWithNext().first { (a, b) -> b - a > 1 }
        assertEquals(between.second, DashManeuverGlyphs.nextCandidate(between.first + 1))
        assertNotNull(seq.firstOrNull())
    }
}
