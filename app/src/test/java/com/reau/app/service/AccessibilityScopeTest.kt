package com.reau.app.service

import com.reau.app.aa.AaScreenRule
import com.reau.app.aa.AndroidAuto
import com.reau.app.phone.CallNotice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Privacy: the accessibility service hears the voice apps (WhatsApp, Telegram, Signal: their call notifications) only
 * while "Atender ligação pelo joystick" is on; Android Auto and Google Maps (the screen it shows) always.
 */
class AccessibilityScopeTest {
    @Test
    fun `with the calls switch off, only Android Auto and Google Maps`() {
        assertEquals(setOf(AndroidAuto.PACKAGE) + AaScreenRule.OWN_DISPLAY_APPS, AccessibilityScope.packages(answerCalls = false))
    }

    @Test
    fun `with it on, the voice apps too`() {
        assertEquals(setOf(AndroidAuto.PACKAGE) + AaScreenRule.OWN_DISPLAY_APPS + CallNotice.VOICE_APPS, AccessibilityScope.packages(answerCalls = true))
    }
}
