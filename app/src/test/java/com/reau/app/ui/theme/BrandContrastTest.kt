package com.reau.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brand mark (the crown and the "au") takes the Ouro of the "Ligar" button in both themes, on the icon and on the
 * REau name in the credits; `brandGold()` is the schemes' primary. Ouro on Marfim is only
 * 1.7:1, so what must stay readable stays in the text colour: the "RE" and the arrow.
 */
class BrandContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `the mark's gold is the Ouro of the main button, in the dark theme and in the light one`() {
        assertEquals(Ouro, REauDark.primary)
        assertEquals(Ouro, REauLight.primary)
    }

    @Test
    fun `the RE and the arrow, in the text colour, read at once on both themes`() {
        assertTrue(contrast(REauLight.onSurface, REauLight.surface) >= 7.0)
        assertTrue(contrast(REauDark.onSurface, REauDark.surface) >= 7.0)
    }

    @Test
    fun `in the dark theme the gold reads at once too`() {
        assertTrue(contrast(Ouro, REauDark.surface) >= 7.0)
    }

    @Test
    fun `the error colour as text reads on every ground it is written on, in both themes`() {
        // Small text needs 4.5:1. The grounds: the page, a card (Carvão in the dark theme) and a dialog.
        for (ground in listOf(REauDark.background, REauDark.surfaceVariant, REauDark.surfaceContainerHigh)) {
            assertTrue("dark, on $ground: ${contrast(REauDark.error, ground)}", contrast(REauDark.error, ground) >= 4.5)
        }
        for (ground in listOf(REauLight.background, REauLight.surfaceContainerHigh, REauLight.primaryContainer)) {
            assertTrue("light, on $ground: ${contrast(REauLight.error, ground)}", contrast(REauLight.error, ground) >= 4.5)
        }
    }

    @Test
    fun `the brand's status red did not read as text, which is why the text has reds of its own, and the icons keep the brand's`() {
        assertTrue(contrast(StatusErro, Carvao) < 4.5)
        assertTrue(contrast(StatusErro, Marfim) < 4.5)
        assertEquals("icons need 3:1 and keep the brand colour", StatusErro, REauStatusDark.error)
        assertEquals(StatusErro, REauStatusLight.error)
        assertTrue(contrast(StatusErro, Marfim) >= 3.0 && contrast(StatusErro, Carvao) >= 3.0)
    }
}
