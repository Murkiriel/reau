package com.reau.app.dash

import com.reau.app.dash.nav.DashManeuverGlyphs
import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * While guiding, the dash's ring always carries a maneuver it can draw (otherwise the ring vanished on a stretch with
 * no maneuver and the joystick stopped until it came back). Waze
 * sends `event=UNKNOWN` every 2-3 s on such stretches (163 times mid-route in a few days of logs), which the
 * conversion gives as the hidden bubble (0xFF), and with 0xFF the dash drops the ring and the map-mode joystick.
 */
class DashSessionRingTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    private fun guide(maneuver: Int) = session.updateNavInfo(maneuver, 250, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS)

    @Test
    fun `a maneuver the dash cannot draw keeps the last one it could`() {
        assertEquals(0x14, DashSession.ringManeuver(DashManeuverGlyphs.HIDDEN_BUBBLE, lastVisible = 0x14))
        assertEquals(0x14, DashSession.ringManeuver(0x5A, lastVisible = 0x14))
    }

    @Test
    fun `with none drawn yet it shows straight on`() {
        assertEquals(DashManeuverGlyphs.STRAIGHT, DashSession.ringManeuver(DashManeuverGlyphs.HIDDEN_BUBBLE, lastVisible = null))
    }

    @Test
    fun `a maneuver the dash can draw goes as it is, arrivals and the rerouting compass included`() {
        for (code in listOf(0x14, DashManeuverGlyphs.ARRIVE, DashManeuverGlyphs.ARRIVE_LEFT, DashManeuverGlyphs.ARRIVE_RIGHT, DashManeuverGlyphs.RECALCULATING)) {
            assertEquals("0x%02X".format(code), code, DashSession.ringManeuver(code, lastVisible = 0x15))
        }
    }

    @Test
    fun `the session never puts the hidden bubble in the ring while guiding`() {
        guide(DashManeuverGlyphs.HIDDEN_BUBBLE)
        assertEquals("nothing drawn yet: straight on", DashManeuverGlyphs.STRAIGHT, session.ringNow)
        guide(0x14)
        assertEquals(0x14, session.ringNow)
        repeat(3) { guide(DashManeuverGlyphs.HIDDEN_BUBBLE) }
        assertEquals("Waze's UNKNOWN keeps the turn", 0x14, session.ringNow)
        guide(DashManeuverGlyphs.RECALCULATING)
        guide(DashManeuverGlyphs.HIDDEN_BUBBLE)
        assertEquals("after the compass, the last turn, not the compass", 0x14, session.ringNow)
    }

    @Test
    fun `a new guidance forgets the last turn of the one before`() {
        guide(0x14)
        session.clearNavInfo()
        guide(DashManeuverGlyphs.HIDDEN_BUBBLE)
        assertEquals(DashManeuverGlyphs.STRAIGHT, session.ringNow)
    }
}
