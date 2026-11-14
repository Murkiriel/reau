package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Test

/** REau takes the rider to Android Auto's settings by itself, once, only where it helps and only with REau on screen. */
class OpensSettingsByItselfTest {
    private fun opens(
        link: AaLinkState = AaLinkState.SERVER_OFF, opened: Boolean = false, onScreen: Boolean = true, installed: Boolean = true,
    ) = AndroidAuto.opensSettingsByItself(link, opened, onScreen, installed)

    @Test
    fun `by default, with the server off, REau on screen and Android Auto installed, it opens`() {
        assertEquals(true, opens())
    }

    @Test
    fun `it opens once for each projection`() {
        assertEquals(false, opens(opened = true))
    }

    @Test
    fun `it never pulls the rider over another app`() {
        assertEquals(false, opens(onScreen = false))
    }

    @Test
    fun `without Android Auto the rider is not sent to a store page by itself`() {
        assertEquals(false, opens(installed = false))
    }

    @Test
    fun `any state of the link but the server being off opens nothing`() {
        AaLinkState.entries.filter { it != AaLinkState.SERVER_OFF }.forEach { assertEquals("$it", false, opens(link = it)) }
    }
}
