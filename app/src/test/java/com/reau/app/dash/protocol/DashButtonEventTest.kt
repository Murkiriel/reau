package com.reau.app.dash.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashButtonEventTest {

    @Test
    fun `0x14 maps to ZoomIn`() {
        assertTrue(DashButtonEvent.fromCode(0x14) is DashButtonEvent.ZoomIn)
    }

    @Test
    fun `0x13 maps to ZoomOut`() {
        assertTrue(DashButtonEvent.fromCode(0x13) is DashButtonEvent.ZoomOut)
    }

    @Test
    fun `0x09 maps to NextTrack`() {
        assertTrue(DashButtonEvent.fromCode(0x09) is DashButtonEvent.NextTrack)
    }

    @Test
    fun `0x0A maps to PrevTrack`() {
        assertTrue(DashButtonEvent.fromCode(0x0A) is DashButtonEvent.PrevTrack)
    }

    @Test
    fun `the media mode's play-pause and volume codes map to their own events`() {
        // Media mode of the dash (firmware 11.63: DOWN = 0x07): middle, up, down.
        assertTrue(DashButtonEvent.fromCode(0x05) is DashButtonEvent.PlayPause)
        assertTrue(DashButtonEvent.fromCode(0x06) is DashButtonEvent.VolumeUp)
        assertTrue(DashButtonEvent.fromCode(0x07) is DashButtonEvent.VolumeDown)
    }

    @Test
    fun `0x18 maps to JoystickIn`() {
        assertTrue(DashButtonEvent.fromCode(0x18) is DashButtonEvent.JoystickIn)
    }

    @Test
    fun `0x12 maps to JoystickBack`() {
        assertTrue(DashButtonEvent.fromCode(0x12) is DashButtonEvent.JoystickBack)
    }

    @Test
    fun `an unrecognized code maps to Unknown carrying that code`() {
        val event = DashButtonEvent.fromCode(0x7F)
        assertTrue(event is DashButtonEvent.Unknown)
        assertEquals(0x7F, event.code)
    }

    @Test
    fun `every mapped event carries its own code back`() {
        val codes = listOf(0x14, 0x13, 0x09, 0x0A, 0x18, 0x12, 0x00, 0xFF)
        for (c in codes) assertEquals(c, DashButtonEvent.fromCode(c).code)
    }

    @Test
    fun `fromByte masks a negative Kotlin Byte to the correct unsigned code`() {
        // 0x94 as a signed Byte is negative (-108); the TLV wire value is unsigned.
        val negativeByte = 0x94.toByte()
        assertEquals(0x94, DashButtonEvent.fromByte(negativeByte).code)
    }

    @Test
    fun `fromByte and fromCode agree for a positive byte`() {
        assertEquals(DashButtonEvent.fromCode(0x14), DashButtonEvent.fromByte(0x14.toByte()))
    }

    @Test
    fun `no known code maps to AnswerCall or RejectCall yet`() {
        // No confirmed dash button code for these — see DashButtonEvent's class doc. Pins that
        // fromCode never accidentally starts producing them for an existing mapped code.
        val allMappedCodes = listOf(0x14, 0x13, 0x09, 0x0A, 0x18, 0x12)
        for (c in allMappedCodes) {
            val event = DashButtonEvent.fromCode(c)
            assertTrue(event !is DashButtonEvent.AnswerCall)
            assertTrue(event !is DashButtonEvent.RejectCall)
        }
    }

    @Test
    fun `AnswerCall and RejectCall carry their own code when constructed directly`() {
        assertEquals(0x99, DashButtonEvent.AnswerCall(0x99).code)
        assertEquals(0x77, DashButtonEvent.RejectCall(0x77).code)
    }

    // ── a golden test of the complete table (0x00 to 0xFF), written BEFORE any
    // joystick-media-mode code — locks in fromCode's own complete mapping in one place,
    // instead of the spot-checks above. ──

    @Test
    fun `the complete 0x00-0xFF table matches today's exact category for every single code`() {
        val known: Map<Int, Class<out DashButtonEvent>> = mapOf(
            0x14 to DashButtonEvent.ZoomIn::class.java,
            0x13 to DashButtonEvent.ZoomOut::class.java,
            0x09 to DashButtonEvent.NextTrack::class.java,
            0x0A to DashButtonEvent.PrevTrack::class.java,
            0x18 to DashButtonEvent.JoystickIn::class.java,
            0x12 to DashButtonEvent.JoystickBack::class.java,
            0x05 to DashButtonEvent.PlayPause::class.java,
            0x06 to DashButtonEvent.VolumeUp::class.java,
            0x07 to DashButtonEvent.VolumeDown::class.java,
        )
        for (code in 0..0xFF) {
            val expected = known[code] ?: DashButtonEvent.Unknown::class.java
            val actual = DashButtonEvent.fromCode(code).javaClass
            assertEquals("code 0x%02X".format(code), expected, actual)
        }
    }
}
