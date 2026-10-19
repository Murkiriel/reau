package com.reau.app.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call notifications of voice apps, as the ride log says them, to learn on the phone how WhatsApp rings before the
 * joystick answers it. Never the caller's name or number.
 */
class CallNoticeTest {

    private fun notice(
        template: String? = CallNotice.CALL_STYLE,
        callType: Int? = 1,
        answer: Boolean = true,
        decline: Boolean = true,
        hangUp: Boolean = false,
        titles: List<String> = listOf("Recusar", "Atender"),
    ) = CallNotice("com.whatsapp", template, callType, answer, decline, hangUp, titles, telecomInCall = true)

    @Test
    fun `a call style notification says its kind and its buttons, never a name`() {
        assertEquals(
            "call notification from com.whatsapp: call style, incoming; answer=yes decline=yes hang up=no; " +
                "actions: Recusar | Atender; Telecom in a call: yes",
            notice().line(),
        )
        assertEquals("ongoing", notice(callType = 2, answer = false, decline = false, hangUp = true).kind)
        assertEquals("screening", notice(callType = 3).kind)
        assertEquals("type 7", notice(callType = 7).kind)
    }

    @Test
    fun `a call notification without the call style says so, and what Telecom saw is said as known or not`() {
        val plain = notice(template = "android.app.Notification\$BigTextStyle", callType = null, answer = false, decline = false)
        assertTrue(plain.line(), plain.line().startsWith("call notification from com.whatsapp: not call style; answer=no decline=no"))
        assertTrue(CallNotice("com.whatsapp", null, null, false, false, false, emptyList(), telecomInCall = null).line()
            .endsWith("actions: none; Telecom in a call: unknown (no permission)"))
    }

    @Test
    fun `only the voice apps' call notifications are kept`() {
        assertTrue(CallNotice.isCall("com.whatsapp", "call"))
        assertTrue(CallNotice.isCall("org.telegram.messenger", "call"))
        assertFalse("a message", CallNotice.isCall("com.whatsapp", "msg"))
        assertFalse("not a voice app", CallNotice.isCall("com.example.game", "call"))
        assertFalse("Android Auto has its own handling", CallNotice.isCall("com.google.android.projection.gearhead", "call"))
    }

    @Test
    fun `the accessibility service listens to Android Auto, to every voice app and to the apps with a display of their own`() {
        val xml = File("src/completa/res/xml/aa_server_accessibility.xml").readText()
        val packages = Regex("""android:packageNames="([^"]*)"""").find(xml)!!.groupValues[1].split(',').map { it.trim() }.toSet()
        // Google Maps too, whose screen is a display of its own (com.reau.app.aa.AaScreenRule)
        assertEquals(setOf("com.google.android.projection.gearhead") + CallNotice.VOICE_APPS + com.reau.app.aa.AaScreenRule.OWN_DISPLAY_APPS, packages)
        assertTrue("Google Maps", "com.google.android.apps.maps" in com.reau.app.aa.AaScreenRule.OWN_DISPLAY_APPS)
        assertTrue("WhatsApp", "com.whatsapp" in CallNotice.VOICE_APPS)
        assertTrue("WhatsApp Business", "com.whatsapp.w4b" in CallNotice.VOICE_APPS)
    }
}
