package com.reau.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legibility floor of the states and the text: letters at 4.5:1, icons and the bridge line at 3:1, on every ground each theme
 * puts them on; no text under 12 sp and no touch target under 48 dp in the theme's own tokens.
 */
class StatusContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    // The grounds a state is written on: the page, the card (Carvão in the dark theme), a selected row's tint.
    private val darkGrounds = listOf(REauDark.background, REauDark.surfaceContainer, REauDark.primaryContainer)
    private val lightGrounds = listOf(REauLight.background, REauLight.surfaceContainer)

    private fun texts(status: REauStatusColors) =
        mapOf("success" to status.successText, "warning" to status.warningText, "info" to status.infoText, "error" to status.errorText)

    private fun icons(status: REauStatusColors) =
        mapOf("success" to status.success, "warning" to status.warning, "info" to status.info, "error" to status.error, "idle" to status.idle)

    @Test
    fun `the state letters read at 4_5 to 1 on every ground of the dark theme`() {
        for ((name, color) in texts(REauStatusDark)) for (ground in darkGrounds) {
            assertTrue("dark $name text on $ground: ${contrast(color, ground)}", contrast(color, ground) >= 4.5)
        }
    }

    @Test
    fun `the state letters read at 4_5 to 1 on every ground of the light theme`() {
        for ((name, color) in texts(REauStatusLight)) for (ground in lightGrounds + REauLight.primaryContainer) {
            assertTrue("light $name text on $ground: ${contrast(color, ground)}", contrast(color, ground) >= 4.5)
        }
    }

    @Test
    fun `the state icons read at 3 to 1 on the pages and cards they are drawn on, in both themes`() {
        for ((name, color) in icons(REauStatusDark)) for (ground in listOf(REauDark.background, REauDark.surfaceContainer)) {
            assertTrue("dark $name icon on $ground: ${contrast(color, ground)}", contrast(color, ground) >= 3.0)
        }
        for ((name, color) in icons(REauStatusLight)) for (ground in lightGrounds) {
            assertTrue("light $name icon on $ground: ${contrast(color, ground)}", contrast(color, ground) >= 3.0)
        }
    }

    @Test
    fun `the brand's states were under the text floor in the dark theme, which is why the letters have their own tones`() {
        // 4.0:1 green and 3.9:1 red on the Carvão card, as measured: the icons keep them, the letters do not use them.
        assertTrue(contrast(StatusSucesso, Carvao) < 4.5)
        assertTrue(contrast(StatusErro, Carvao) < 4.5)
        assertTrue(contrast(StatusAtencao, Carvao) < 4.5)
        assertTrue(contrast(StatusInfo, Carvao) < 4.5)
        assertEquals(StatusSucesso, REauStatusDark.success)
        assertEquals(StatusAtencao, REauStatusDark.warning)
        assertEquals(StatusInfo, REauStatusDark.info)
        assertEquals(ErroTextoNoEscuro, REauStatusDark.errorText)
    }

    @Test
    fun `the dark theme's letter tones stay close to the brand's, only lighter`() {
        for ((letters, brand) in listOf(
            REauStatusDark.successText to StatusSucesso, REauStatusDark.warningText to StatusAtencao,
            REauStatusDark.infoText to StatusInfo, REauStatusDark.errorText to StatusErro,
        )) {
            assertTrue("$letters is lighter than $brand", letters.luminance() > brand.luminance())
            assertTrue("$letters stays in $brand's hue family", contrast(letters, brand) < 2.0)
        }
    }

    @Test
    fun `supporting text and the muted text colour read at 4_5 to 1 in both themes`() {
        for (scheme in listOf(REauDark, REauLight)) {
            for (ground in listOf(scheme.background, scheme.surfaceContainer, scheme.primaryContainer)) {
                assertTrue("onSurface on $ground", contrast(scheme.onSurface, ground) >= 4.5)
                assertTrue("onSurfaceVariant on $ground: ${contrast(scheme.onSurfaceVariant, ground)}", contrast(scheme.onSurfaceVariant, ground) >= 4.5)
            }
        }
        // The warning card's body text is onSurface at 78% (dark) or 72% (light) over the card.
        assertTrue(contrast(REauDark.onSurface.copy(alpha = 0.78f).compositeOver(REauDark.surfaceContainer), REauDark.surfaceContainer) >= 4.5)
        assertTrue(contrast(REauLight.onSurface.copy(alpha = 0.72f).compositeOver(REauLight.surfaceContainer), REauLight.surfaceContainer) >= 4.5)
    }

    @Test
    fun `no style of the theme is under 12 sp and the touch target is 48 dp`() {
        val styles = with(REauTypography) {
            listOf(
                displaySmall, headlineLarge, headlineMedium, headlineSmall, titleLarge, titleMedium, titleSmall,
                bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall,
            ) + listOf(REauMono, REauMonoCompact, REauCounterValue)
        }
        for (style in styles) assertTrue("${style.fontSize} is under 12 sp", style.fontSize.value >= 12f)
        assertEquals(48f, REauSpace.touch.value, 0f)
    }
}
