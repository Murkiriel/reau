package com.reau.app.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answering and ending a real call by [PhoneCalls] (the joystick answers calls). Only on
 * the emulator and only on purpose: the host first makes the emulator ring (`adb emu gsm call 5551234`), then runs this
 * class with `-e ringing 1`. Without that argument it is skipped (it would wait for a call that never comes).
 */
@RunWith(AndroidJUnit4::class)
class PhoneCallsOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun waitFor(phone: PhoneCalls, want: CallState, timeoutMs: Long): CallState {
        val end = System.currentTimeMillis() + timeoutMs
        var now = phone.state()
        while (now != want && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            now = phone.state()
        }
        return now
    }

    @Test
    fun theRingingCallIsSeenAnsweredAndEnded() {
        assumeTrue("needs a ringing emulator: adb emu gsm call, then -e ringing 1", InstrumentationRegistry.getArguments().getString("ringing") == "1")
        PhoneCalls.PERMISSIONS.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val phone = PhoneCalls(context)
        assertTrue(phone.permitted())
        assertEquals(CallState.RINGING, waitFor(phone, CallState.RINGING, 20_000))
        assertTrue(phone.answer())
        assertEquals(CallState.IN_CALL, waitFor(phone, CallState.IN_CALL, 10_000))
        assertTrue(phone.end())
        assertEquals(CallState.IDLE, waitFor(phone, CallState.IDLE, 10_000))
    }
}
