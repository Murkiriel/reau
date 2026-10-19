package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A voice app's call (WhatsApp) must be answered by the joystick too: the joystick's answer and end go to Android
 * Auto as a car's phone keys, which answer on Android Auto's own call card the phone's calls and the voice apps'
 * alike; the system's Telecom, which cannot answer a voice app's call, comes after.
 */
class AaCallKeysTest {

    @Test
    fun `with Android Auto on, answer is the call key and end the end-call key`() {
        assertEquals(listOf(AaCommand.Key(ServiceDiscoveryResponse.KEY_CALL)), AaCallKeys.toAndroidAuto(AaCommand.AnswerCall, androidAuto = true))
        assertEquals(listOf(AaCommand.Key(ServiceDiscoveryResponse.KEY_ENDCALL)), AaCallKeys.toAndroidAuto(AaCommand.EndCall, androidAuto = true))
    }

    @Test
    fun `without Android Auto nothing goes to it`() {
        assertEquals(emptyList<AaCommand>(), AaCallKeys.toAndroidAuto(AaCommand.AnswerCall, androidAuto = false))
        assertEquals(emptyList<AaCommand>(), AaCallKeys.toAndroidAuto(AaCommand.EndCall, androidAuto = false))
    }

    @Test
    fun `other commands are not call keys`() {
        assertEquals(emptyList<AaCommand>(), AaCallKeys.toAndroidAuto(AaCommand.Key(ServiceDiscoveryResponse.KEY_ENTER), androidAuto = true))
        assertEquals(emptyList<AaCommand>(), AaCallKeys.toAndroidAuto(AaCommand.Scroll(1), androidAuto = true))
    }

    @Test
    fun `the keys are Android's own call and end-call`() {
        assertEquals(5, ServiceDiscoveryResponse.KEY_CALL)
        assertEquals(6, ServiceDiscoveryResponse.KEY_ENDCALL)
    }
}
