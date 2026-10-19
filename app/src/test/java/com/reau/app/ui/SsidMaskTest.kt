package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Wi-Fi name is masked, so it does not show directly, only when the rider asks for it. */
class SsidMaskTest {
    @Test
    fun `the beginning every dash shares and the last two letters show, never the rest`() {
        assertEquals("RE_••••78", SsidMask.of("RE_AB12_345678"))
        assertFalse(SsidMask.of("RE_AB12_345678").contains("AB12"))
    }

    @Test
    fun `the mask says nothing of the length`() {
        assertEquals(SsidMask.of("RE_A_12").length, SsidMask.of("RE_AB12_345678").length)
    }

    @Test
    fun `a short name keeps only its first letter`() {
        assertEquals("R••••", SsidMask.of("RE_1"))
        assertEquals("", SsidMask.of(""))
    }
}
