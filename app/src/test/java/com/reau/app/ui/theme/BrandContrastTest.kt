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
}
