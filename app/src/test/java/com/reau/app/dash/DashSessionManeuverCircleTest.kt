package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Test

class DashSessionManeuverCircleTest {

    @Test
    fun `circle on sends the real maneuver`() {
        assertEquals(0x09, DashSession.bubbleManeuver(circleEnabled = true, maneuver = 0x09))
        assertEquals(0x3C, DashSession.bubbleManeuver(circleEnabled = true, maneuver = 0x3C))
    }

    @Test
    fun `circle off always hides the bubble`() {
        for (m in listOf(0x09, 0x3C, 0x0B)) {
            assertEquals(DashCommands.NAV_MANEUVER_NONE, DashSession.bubbleManeuver(circleEnabled = false, maneuver = m))
        }
    }
}
