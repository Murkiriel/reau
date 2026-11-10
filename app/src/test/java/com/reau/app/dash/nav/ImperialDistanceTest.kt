package com.reau.app.dash.nav

import com.reau.app.aa.NavGuidance
import com.reau.app.dash.DashReport
import com.reau.app.dash.DashSession
import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maneuver distance with the dash in miles: feet up to 550 of them (0x50), above that tenths of a mile (0x20), 3.281 feet to the metre; metric as before
 * (0x30 metres under a kilometre, 0x10 tenths of a kilometre). The unit of the dash decides, the phone's region only before the dash has said it.
 */
class ImperialDistanceTest {
    private val feet = DashCommands.NAV_UNIT_FEET
    private val mileTenths = DashCommands.NAV_UNIT_MILE_TENTHS

    @Test
    fun `the new unit bytes are the maker's 0x20 and 0x50, the old ones unchanged`() {
        assertEquals(0x20, mileTenths)
        assertEquals(0x50, feet)
        assertEquals(0x10, DashCommands.NAV_UNIT_KM_TENTHS)
        assertEquals(0x30, DashCommands.NAV_UNIT_METERS)
    }

    @Test
    fun `short distances go in feet`() {
        assertEquals(0 to feet, AaManeuvers.distance(0, imperial = true))
        assertEquals(33 to feet, AaManeuvers.distance(10, imperial = true)) // 32.81
        assertEquals(164 to feet, AaManeuvers.distance(50, imperial = true)) // 164.05
        assertEquals(328 to feet, AaManeuvers.distance(100, imperial = true)) // 328.1
        assertEquals(525 to feet, AaManeuvers.distance(160, imperial = true)) // 524.96
    }

    @Test
    fun `the limit is 550 feet, up to it feet and above it tenths of a mile`() {
        // 167 m is 547.9 ft, 167.6 m is 549.9 ft, 168 m is 551.2 ft.
        assertEquals(548 to feet, AaManeuvers.distance(167, imperial = true))
        assertEquals(1 to mileTenths, AaManeuvers.distance(168, imperial = true)) // 551 ft: 0.104 mi, 1 tenth
        assertEquals(1 to mileTenths, AaManeuvers.distance(200, imperial = true))
    }

    @Test
    fun `long distances go in tenths of a mile, rounded`() {
        assertEquals(2 to mileTenths, AaManeuvers.distance(300, imperial = true)) // 0.186 mi
        assertEquals(6 to mileTenths, AaManeuvers.distance(1_000, imperial = true)) // 0.621
        assertEquals(16 to mileTenths, AaManeuvers.distance(2_575, imperial = true)) // 1.600
        assertEquals(124 to mileTenths, AaManeuvers.distance(20_000, imperial = true)) // 12.43
        assertEquals("the field is 16 bits", 0xFFFF to mileTenths, AaManeuvers.distance(1_000_000_000, imperial = true))
    }

    @Test
    fun `an unknown distance is zero feet when imperial, zero metres when metric`() {
        assertEquals(0 to feet, AaManeuvers.distance(-1, imperial = true))
        assertEquals(0 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(-1))
        assertEquals(0 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(-1, imperial = false))
    }

    @Test
    fun `metric is what it was`() {
        assertEquals(250 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(250))
        assertEquals(999 to DashCommands.NAV_UNIT_METERS, AaManeuvers.distance(999, imperial = false))
        assertEquals(10 to DashCommands.NAV_UNIT_KM_TENTHS, AaManeuvers.distance(1_000))
        assertEquals(123 to DashCommands.NAV_UNIT_KM_TENTHS, AaManeuvers.distance(12_345, imperial = false))
    }

    private fun guidance() = NavGuidance(
        active = true, maneuverType = 7, distanceToManeuverMeters = 120, distanceRemainingMeters = 8_000, estimatedTimeAtArrival = "14:35",
        timeToArrivalSeconds = 600,
    )

    @Test
    fun `the maneuver and the total both follow the unit`() {
        val imperial = AaManeuvers.toDash(guidance(), imperial = true)!!
        assertEquals(394, imperial.primaryDist) // 120 m = 393.7 ft
        assertEquals(feet, imperial.primaryUnit)
        assertEquals(50, imperial.totalDist) // 8 km = 4.97 mi
        assertEquals(mileTenths, imperial.totalUnit)
        val metric = AaManeuvers.toDash(guidance())!!
        assertEquals(120, metric.primaryDist)
        assertEquals(DashCommands.NAV_UNIT_METERS, metric.primaryUnit)
        assertEquals(80, metric.totalDist)
        assertEquals(DashCommands.NAV_UNIT_KM_TENTHS, metric.totalUnit)
    }

    /** The value bytes of the TLV ([type], [sub]) of an outgoing packet (its TLVs start after the 17-byte header). */
    private fun valueOf(pkt: ByteArray, type: Int, sub: Int): List<Int> {
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if ((pkt[i].toInt() and 0xFF) == type && (pkt[i + 1].toInt() and 0xFF) == sub) {
                return pkt.copyOfRange(i + 4, i + 4 + len).map { it.toInt() and 0xFF }
            }
            i += 4 + len
        }
        error("no TLV %02X/%02X".format(type, sub))
    }

    @Test
    fun `the nav packet carries 05 04 and 05 09 with the unit in 05 06 and 05 46`() {
        val pkt = DashCommands.activeNavPacket(
            maneuver = 0x14, primaryDist = 394, primaryUnit = feet, totalDist = 50, totalUnit = mileTenths,
        )
        assertEquals(listOf(0x01, 0x8A), valueOf(pkt, 5, 0x04)) // 394
        assertEquals(listOf(feet), valueOf(pkt, 5, 0x06))
        assertEquals(listOf(0x00, 0x32), valueOf(pkt, 5, 0x09))
        assertEquals(listOf(mileTenths), valueOf(pkt, 5, 0x46))
    }

    @Test
    fun `the route card carries the total and the units too`() {
        val pkt = DashCommands.routeCard(
            "Rua", projectionOn = true, maneuver = 0x14, primaryUnit = feet, totalDist = 50, totalUnit = mileTenths,
        )
        assertEquals(listOf(feet), valueOf(pkt, 5, 0x06))
        assertEquals(listOf(0x00, 0x32), valueOf(pkt, 5, 0x09))
        assertEquals(listOf(mileTenths), valueOf(pkt, 5, 0x46))
    }

    @Test
    fun `a field in any unit comes back as metres for the proximity bar`() {
        assertEquals(250, DashCommands.navMeters(250, DashCommands.NAV_UNIT_METERS))
        assertEquals(4_400, DashCommands.navMeters(44, DashCommands.NAV_UNIT_KM_TENTHS))
        assertEquals(161, DashCommands.navMeters(1, mileTenths))
        assertEquals(805, DashCommands.navMeters(5, mileTenths))
        assertEquals(30, DashCommands.navMeters(98, feet)) // 29.87 m
        assertEquals(0, DashCommands.navMeters(0, feet))
    }

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun barOf(meters: Int, imperial: Boolean): Int {
        val session = DashSession(scope)
        val (value, unit) = AaManeuvers.distance(meters, imperial)
        session.updateNavInfo(0x14, value, unit, 32, DashCommands.NAV_UNIT_KM_TENTHS)
        val pkt = session.routeCardNow()
        return valueOf(pkt, 5, 0x0C).single()
    }

    @Test
    fun `the proximity bar is the same for a turn in metres and in feet or tenths of a mile (away from the 500 m edge, where a tenth of a mile is 161 m)`() {
        for (m in listOf(10, 15, 40, 70, 95, 300, 480, 900, 3_000)) {
            assertEquals("$m m", barOf(m, imperial = false), barOf(m, imperial = true))
        }
    }

    @Test
    fun `the dash's own unit decides, the phone's region only before it has said one`() {
        assertTrue(NavUnits.imperial(DashReport.DistanceUnit.MILES, "BR"))
        assertFalse(NavUnits.imperial(DashReport.DistanceUnit.KM, "US"))
        assertTrue(NavUnits.imperial(null, "US"))
        assertTrue(NavUnits.imperial(null, "gb"))
        assertTrue(NavUnits.imperial(null, "LR"))
        assertTrue(NavUnits.imperial(null, "MM"))
        assertFalse(NavUnits.imperial(null, "BR"))
        assertFalse(NavUnits.imperial(null, "DE"))
        assertFalse(NavUnits.imperial(null, ""))
        assertFalse(NavUnits.imperial(null, null))
    }

    @Test
    fun `the log line says the unit and why`() {
        assertEquals(
            "nav distances in feet and tenths of a mile (the dash says miles)",
            NavUnits.line(true, DashReport.DistanceUnit.MILES, "BR"),
        )
        assertEquals(
            "nav distances in metres and tenths of a kilometre (the dash has not said yet, the phone's region is BR)",
            NavUnits.line(false, null, "BR"),
        )
    }
}
