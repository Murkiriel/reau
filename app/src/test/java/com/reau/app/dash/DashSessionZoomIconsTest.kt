package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * After the echo of a zoom or recentre press the session tells the dash its zoom state (06 0C), so its ZOOM shows "-"
 * and "+": always "both icons" (Android Auto's zoom ends are not known here). Other presses get the echo only.
 */
class DashSessionZoomIconsTest {
    private fun replies(code: Int) = DashSession.repliesToButton(code.toByte())

    @Test
    fun `a zoom or recentre press gets the echo, then the zoom icons`() {
        for (code in listOf(0x13, 0x14, 0x15)) {
            val out = replies(code)
            assertEquals("0x%02X".format(code), 2, out.size)
            assertArrayEquals(DashCommands.buttonAck(code.toByte()), out[0])
            assertArrayEquals(DashCommands.zoomIcons(), out[1])
        }
    }

    @Test
    fun `any other press gets the echo only`() {
        for (code in listOf(0x05, 0x06, 0x07, 0x09, 0x0A, 0x0B, 0x12, 0x16, 0x18, 0x22)) {
            val out = replies(code)
            assertEquals("0x%02X".format(code), 1, out.size)
            assertArrayEquals(DashCommands.buttonAck(code.toByte()), out[0])
        }
    }
}
