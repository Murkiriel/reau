package com.reau.app.dash.nav

import com.reau.app.aa.NavGuidance
import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto's turn-by-turn guidance as the dash's own nav bubble shows it: the maneuver
 * type Android Auto sends (NavigationManeuver.NavigationType, its wire numbers) becomes the dash's glyph byte through
 * the glyph catalog ([DashManeuverGlyphs]), and the distances become the dash's value-and-unit pairs.
 */
class AaManeuversTest {

    private fun glyph(type: Int, exit: Int = 0) = AaManeuvers.glyphFor(type, exit)

    @Test
    fun `straight ahead, departing and a new road name are the straight arrow`() {
        for (type in listOf(1, 2, 36)) assertEquals("type $type", DashManeuverGlyphs.STRAIGHT, glyph(type))
    }

    @Test
    fun `turns of every sharpness on both sides`() {
        assertEquals(DashManeuverGlyphs.SLIGHT_LEFT, glyph(5))
        assertEquals(DashManeuverGlyphs.SLIGHT_RIGHT, glyph(6))
        assertEquals(DashManeuverGlyphs.TURN_LEFT, glyph(7))
        assertEquals(DashManeuverGlyphs.TURN_RIGHT, glyph(8))
        assertEquals(DashManeuverGlyphs.SHARP_LEFT, glyph(9))
        assertEquals(DashManeuverGlyphs.SHARP_RIGHT, glyph(10))
    }

    @Test
    fun `U-turns on the side Android Auto names`() {
        assertEquals(DashManeuverGlyphs.UTURN_LEFT, glyph(11))
        assertEquals(DashManeuverGlyphs.UTURN_RIGHT, glyph(12))
        assertEquals(DashManeuverGlyphs.UTURN_LEFT, glyph(19))
        assertEquals(DashManeuverGlyphs.UTURN_RIGHT, glyph(20))
    }

    @Test
    fun `ramps on and off are drawn as the turn they are`() {
        assertEquals(listOf(0x18, 0x19, 0x14, 0x15, 0x16, 0x17), (13..18).map { glyph(it) })
        assertEquals(listOf(0x18, 0x19, 0x14, 0x15), (21..24).map { glyph(it) })
    }

    @Test
    fun `keeping to a side and forks are the Y fork`() {
        assertEquals(DashManeuverGlyphs.FORK_LEFT, glyph(3))
        assertEquals(DashManeuverGlyphs.FORK_RIGHT, glyph(4))
        assertEquals(DashManeuverGlyphs.FORK_LEFT, glyph(25))
        assertEquals(DashManeuverGlyphs.FORK_RIGHT, glyph(26))
    }

    @Test
    fun `merges go through the catalog's rule, an unspecified side is straight on`() {
        assertEquals(DashManeuverGlyphs.codeFor(ManeuverType.MERGE_LEFT), glyph(27))
        assertEquals(DashManeuverGlyphs.codeFor(ManeuverType.MERGE_RIGHT), glyph(28))
        assertEquals(DashManeuverGlyphs.STRAIGHT, glyph(29))
    }

    @Test
    fun `roundabouts carry their exit and their direction`() {
        // Counter-clockwise (right-hand traffic) unless Android Auto says clockwise.
        for (type in listOf(30, 31, 34, 35)) assertEquals("type $type", DashManeuverGlyphs.roundabout(2, clockwise = false), glyph(type, exit = 2))
        for (type in listOf(32, 33)) assertEquals("type $type", DashManeuverGlyphs.roundabout(2, clockwise = true), glyph(type, exit = 2))
        assertEquals("no exit known: the plain circle", DashManeuverGlyphs.roundabout(0, clockwise = false), glyph(30, exit = 0))
        assertEquals(0x0C, glyph(34, exit = 2))
    }

    @Test
    fun `ferries and arrivals`() {
        assertEquals(0x3E, glyph(37))
        assertEquals(0x3F, glyph(38))
        assertEquals(DashManeuverGlyphs.ARRIVE, glyph(39))
        assertEquals(DashManeuverGlyphs.ARRIVE, glyph(40))
        assertEquals(DashManeuverGlyphs.ARRIVE_LEFT, glyph(41))
        assertEquals(DashManeuverGlyphs.ARRIVE_RIGHT, glyph(42))
    }

    @Test
    fun `an unknown maneuver hides the bubble`() {
        for (type in listOf(0, -1, 43, 99)) assertEquals("type $type", DashManeuverGlyphs.HIDDEN_BUBBLE, glyph(type))
    }

    @Test
    fun `every known maneuver draws something`() {
        for (type in 1..42) assertTrue("type $type: ${glyph(type)}", DashManeuverGlyphs.isVisible(glyph(type)))
    }

    @Test
    fun `rerouting shows the spinning compass, whatever the maneuver`() {
        assertEquals(DashManeuverGlyphs.RECALCULATING, AaManeuvers.glyphFor(7, 0, rerouting = true))
    }

    @Test
    fun `distances under a kilometre in metres, from there in tenths of a kilometre`() {
        assertEquals(250 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(250))
        assertEquals(999 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(999))
        assertEquals(10 to DashCommands.NAV_UNIT_KM_TENTHS, AaManeuvers.distance(1_000))
        assertEquals(123 to DashCommands.NAV_UNIT_KM_TENTHS, AaManeuvers.distance(12_345))
        assertEquals("rounded", 13 to DashCommands.NAV_UNIT_KM_TENTHS, AaManeuvers.distance(1_250))
        assertEquals("the field is 16 bits", 0xFFFF to DashCommands.NAV_UNIT_KM_TENTHS, AaManeuvers.distance(10_000_000))
        assertEquals("unknown", 0 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(-1))
    }

    @Test
    fun `no guidance, nothing for the dash`() {
        assertNull(AaManeuvers.toDash(NavGuidance.INACTIVE))
    }

    @Test
    fun `guidance becomes the dash's nav figures`() {
        val g = NavGuidance(
            active = true, maneuverType = 7, distanceToManeuverMeters = 250, distanceRemainingMeters = 3_200,
            estimatedTimeAtArrival = "14:35",
        )
        assertEquals(
            AaManeuvers.DashNav(0x14, 250, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, "14:35", remainingSeconds = 2_160L),
            AaManeuvers.toDash(g, now = java.time.LocalTime.of(14, 0)),
        )
    }

    @Test
    fun `a time of arrival not in hours and minutes is left out`() {
        val g = NavGuidance(active = true, maneuverType = 36, estimatedTimeAtArrival = "2:35 PM")
        assertNull(AaManeuvers.toDash(g)?.eta)
        assertEquals("9:05", AaManeuvers.toDash(g.copy(estimatedTimeAtArrival = "9:05"))?.eta)
    }

    @Test
    fun `the roundabout exit and rerouting reach the glyph`() {
        val g = NavGuidance(active = true, maneuverType = 34, roundaboutExitNumber = 3, rerouting = false)
        assertEquals(0x0D, AaManeuvers.toDash(g)?.maneuver)
        assertEquals(DashManeuverGlyphs.RECALCULATING, AaManeuvers.toDash(g.copy(rerouting = true))?.maneuver)
    }

    // -- the time left and the lost-signal icon -----------------------------------------------------------------------

    private fun guiding(rerouting: Boolean = false) = NavGuidance(
        active = true, rerouting = rerouting, maneuverType = 7, distanceToManeuverMeters = 300, distanceRemainingMeters = 5_000,
        timeToArrivalSeconds = 1_500, nextManeuverType = 8,
    )

    @Test
    fun `the dash gets the time left and the maneuver after the next`() {
        val nav = AaManeuvers.toDash(guiding())!!
        assertEquals(1_500L, nav.remainingSeconds)
        assertEquals(DashManeuverGlyphs.TURN_RIGHT, nav.next)
    }

    @Test
    fun `no maneuver after the next, or one the dash cannot draw, is none`() {
        assertEquals(DashCommands.NEXT_MANEUVER_NONE, AaManeuvers.toDash(guiding().copy(nextManeuverType = -1))!!.next)
        assertEquals(DashCommands.NEXT_MANEUVER_NONE, AaManeuvers.toDash(guiding().copy(nextManeuverType = 999))!!.next)
    }

    @Test
    fun `an unknown time left stays unknown`() {
        assertEquals(DashCommands.REMAINING_OMIT, AaManeuvers.toDash(guiding().copy(timeToArrivalSeconds = -1))!!.remainingSeconds)
    }

    // Waze never says the time left in seconds, only the arrival time as "08:01": the time left is that minus now, from the
    // middle of the arrival's minute, in whole minutes.
    private fun now(h: Int, m: Int, s: Int = 0) = java.time.LocalTime.of(h, m, s)

    @Test
    fun `with no seconds from Android Auto the time left is the arrival time minus now`() {
        val g = guiding().copy(timeToArrivalSeconds = -1, estimatedTimeAtArrival = "08:01")
        assertEquals("08:01:30 - 07:57:30", 240L, AaManeuvers.toDash(g, now = now(7, 57, 30))!!.remainingSeconds)
        assertEquals("61.5 min rounds to 62", 3_720L, AaManeuvers.toDash(g, now = now(7, 0))!!.remainingSeconds)
        assertEquals("across midnight", 3_660L, AaManeuvers.toDash(g.copy(estimatedTimeAtArrival = "0:30"), now = now(23, 30))!!.remainingSeconds)
    }

    @Test
    fun `the time left is what the navigation app shows, counted from the middle of the arrival's minute`() {
        // (arrival, clock, the app's "N min"); counted from hh:mm:00 each would be one minute short
        val checks = listOf(
            Triple("14:32", now(14, 20, 29), 12), Triple("14:31", now(14, 22, 29), 9), Triple("14:31", now(14, 24, 30), 7),
            Triple("14:31", now(14, 26, 31), 5), Triple("14:30", now(14, 27, 51), 3), Triple("14:30", now(14, 28, 31), 2),
        )
        for ((eta, clock, wazeMin) in checks) {
            val g = guiding().copy(timeToArrivalSeconds = -1, estimatedTimeAtArrival = eta)
            assertEquals("$eta at $clock", wazeMin * 60L, AaManeuvers.toDash(g, now = clock)!!.remainingSeconds)
        }
    }

    @Test
    fun `an arrival time that has just passed is 0, not a day`() {
        val g = guiding().copy(timeToArrivalSeconds = -1, estimatedTimeAtArrival = "08:00")
        assertEquals(0L, AaManeuvers.toDash(g, now = now(8, 0, 40))!!.remainingSeconds)
    }

    @Test
    fun `the seconds from Android Auto win over the arrival time, and neither is unknown`() {
        val g = guiding().copy(estimatedTimeAtArrival = "08:01") // seconds 1500
        assertEquals(1_500L, AaManeuvers.toDash(g, now = now(7, 57))!!.remainingSeconds)
        val none = guiding().copy(timeToArrivalSeconds = -1, estimatedTimeAtArrival = "")
        assertEquals(DashCommands.REMAINING_OMIT, AaManeuvers.toDash(none, now = now(7, 57))!!.remainingSeconds)
    }

    @Test
    fun `recalculating with no network is the lost-signal icon, anything else is as before`() {
        assertEquals(DashManeuverGlyphs.SIGNAL_LOST, AaManeuvers.toDash(guiding(rerouting = true), noNetwork = true)!!.maneuver)
        assertEquals(DashManeuverGlyphs.RECALCULATING, AaManeuvers.toDash(guiding(rerouting = true), noNetwork = false)!!.maneuver)
        assertEquals("a real turn is never hidden by the lack of network", DashManeuverGlyphs.TURN_LEFT, AaManeuvers.toDash(guiding(), noNetwork = true)!!.maneuver)
    }
}
