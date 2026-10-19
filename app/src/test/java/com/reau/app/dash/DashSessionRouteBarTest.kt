package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A ride log showed the route card saying bar 4 while the nav packet said 5 or 6, every second: the card carries the
 * same proximity bar as the nav packet, whatever the "Rota real" switch says.
 */
class DashSessionRouteBarTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    private fun barOfCard(): Int {
        val pkt = session.routeCardNow()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if ((pkt[i].toInt() and 0xFF) == 5 && (pkt[i + 1].toInt() and 0xFF) == 0x0C) return pkt[i + 4].toInt() and 0xFF
            i += 4 + len
        }
        error("no 05 0C in the card")
    }

    private fun guide(meters: Int, unit: Int = DashCommands.NAV_UNIT_METERS) =
        session.updateNavInfo(0x14, meters, unit, 32, DashCommands.NAV_UNIT_KM_TENTHS)

    @Test
    fun `far from the turn the card says bar 5, as the nav packet`() {
        guide(44, DashCommands.NAV_UNIT_KM_TENTHS) // 4.4 km: the unit that sent 05 beside a 04 in that log
        assertEquals(DashCommands.proximityLevel(4_400), barOfCard())
        assertEquals(5, barOfCard())
    }

    @Test
    fun `within 500 m the card says bar 6 and near the turn it fills`() {
        guide(300)
        assertEquals(6, barOfCard())
        guide(15)
        assertEquals(4, barOfCard())
        guide(70)
        assertEquals(DashCommands.proximityLevel(70), barOfCard())
    }
}
