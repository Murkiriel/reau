package com.reau.app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * So that calls and a helmet communicator can be tested with the log watched live, each change of the call says where
 * its sound goes (the phone's ear, the speaker, a Bluetooth headset), in numbers and names of kinds only, never a
 * device's name or address.
 */
class CallAudioRouteTest {
    @Test
    fun `the line says the mode, the device kind, the headset flag and the volume`() {
        assertEquals(
            "audio route: mode=IN_CALL device=BLUETOOTH_SCO sco=true volume=5/7",
            CallAudioRoute.line(mode = 2, deviceType = 7, scoOn = true, volume = 5, max = 7),
        )
        assertEquals(
            "audio route: mode=NORMAL device=BUILTIN_SPEAKER sco=false volume=3/7",
            CallAudioRoute.line(mode = 0, deviceType = 2, scoOn = false, volume = 3, max = 7),
        )
    }

    @Test
    fun `the modes of a ringing and a call have their names, and an unknown one its number`() {
        assertEquals("RINGTONE", CallAudioRoute.modeName(1))
        assertEquals("IN_CALL", CallAudioRoute.modeName(2))
        assertEquals("IN_COMMUNICATION", CallAudioRoute.modeName(3))
        assertEquals("mode 9", CallAudioRoute.modeName(9))
    }

    @Test
    fun `the device kinds that matter on a bike have names, any other its number, and none says none`() {
        assertEquals("BUILTIN_EARPIECE", CallAudioRoute.deviceName(1))
        assertEquals("WIRED_HEADSET", CallAudioRoute.deviceName(3))
        assertEquals("BLUETOOTH_SCO", CallAudioRoute.deviceName(7))
        assertEquals("BLUETOOTH_A2DP", CallAudioRoute.deviceName(8))
        assertEquals("BLE_HEADSET", CallAudioRoute.deviceName(26))
        assertEquals("type 99", CallAudioRoute.deviceName(99))
        assertEquals("none", CallAudioRoute.deviceName(null))
    }

    @Test
    fun `the line never carries a name or an address`() {
        val line = CallAudioRoute.line(mode = 2, deviceType = 7, scoOn = true, volume = 5, max = 7)
        assertFalse(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").containsMatchIn(line))
        assertTrue(line.startsWith("audio route: "))
    }
}
