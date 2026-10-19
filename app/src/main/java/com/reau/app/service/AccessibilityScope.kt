package com.reau.app.service

import com.reau.app.aa.AaScreenRule
import com.reau.app.aa.AndroidAuto
import com.reau.app.phone.CallNotice

/**
 * Which apps the accessibility service hears (kept as narrow as possible for privacy). The manifest
 * declares the most it may ever hear (res/xml/aa_server_accessibility.xml); the running service narrows it to this:
 * Android Auto and Google Maps (the screen Android Auto shows) always, the voice apps' call notifications only while
 * "Atender ligação pelo joystick" is on.
 */
object AccessibilityScope {
    fun packages(answerCalls: Boolean): Set<String> =
        setOf(AndroidAuto.PACKAGE) + AaScreenRule.OWN_DISPLAY_APPS + if (answerCalls) CallNotice.VOICE_APPS else emptySet()
}
