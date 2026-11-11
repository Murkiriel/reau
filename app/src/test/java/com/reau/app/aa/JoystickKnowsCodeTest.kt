package com.reau.app.aa

import com.reau.app.dash.protocol.DashButtonEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which button codes of the dash REau has a meaning for, for the projection's `resumo` (`buttons unknown=`): the ones [DashButtonEvent] names, and the
 * ones the page reads from an unnamed code (the dash's notice 0x0B, the call volume 0x03 and 0x04, the call screen's 0x01, 0x02 and 0x16, the map's
 * down 0x15). Anything else is a code nobody explained, 0x20 among them.
 */
class JoystickKnowsCodeTest {
    private fun knows(code: Int) = JoystickMap.knowsCode(DashButtonEvent.fromCode(code))

    @Test
    fun `every code the page uses is known`() {
        for (code in listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x0B, 0x12, 0x13, 0x14, 0x15, 0x16, 0x18)) {
            assertEquals("0x%02X".format(code), true, knows(code))
        }
    }

    @Test
    fun `a code nobody explained is not, the one reported from the dash's menu included`() {
        for (code in listOf(0x00, 0x08, 0x0C, 0x10, 0x11, 0x17, 0x19, 0x20, 0x31, 0xFF)) assertEquals("0x%02X".format(code), false, knows(code))
    }

    /** What the page reads from an unnamed code and what the summary counts as known must come from the same list. */
    @Test
    fun `a code the page reads from an unnamed event is known to the summary`() {
        for (code in 0..0xFF) {
            val event = DashButtonEvent.fromCode(code)
            if (event is DashButtonEvent.Unknown) {
                val place = JoystickMap.whereOf(event) != null
                if (place) assertEquals("0x%02X has a place on the page", true, JoystickMap.knowsCode(event))
            }
        }
    }
}
