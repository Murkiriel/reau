package com.reau.app.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's call as the joystick needs it, to answer calls by the joystick. */
class PhoneCallsTest {
    @Test
    fun `ringing wins over a call already going on, nothing is idle`() {
        assertEquals(CallState.RINGING, PhoneCalls.stateOf(ringing = true, inCall = true))
        assertEquals(CallState.RINGING, PhoneCalls.stateOf(ringing = true, inCall = false))
        assertEquals(CallState.IN_CALL, PhoneCalls.stateOf(ringing = false, inCall = true))
        assertEquals(CallState.IDLE, PhoneCalls.stateOf(ringing = false, inCall = false))
    }

    @Test
    fun `with the switch off or without the permissions the joystick never sees a call`() {
        assertEquals(CallState.IDLE, PhoneCalls.visibleState(switchOn = false, permitted = true, CallState.RINGING))
        assertEquals(CallState.IDLE, PhoneCalls.visibleState(switchOn = true, permitted = false, CallState.RINGING))
        assertEquals(CallState.RINGING, PhoneCalls.visibleState(switchOn = true, permitted = true, CallState.RINGING))
        assertEquals(CallState.IN_CALL, PhoneCalls.visibleState(switchOn = true, permitted = true, CallState.IN_CALL))
    }
}
