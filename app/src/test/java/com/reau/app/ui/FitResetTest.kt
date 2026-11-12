package com.reau.app.ui

import com.reau.app.settings.Fit
import com.reau.app.settings.REauSettings
import com.reau.app.video.FrameFit
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Voltar ao padrão" on the Android Auto screen (12/11/2026): in sight at the top of the fit, it asks first, and then takes the fit, the crop,
 * the zoom and the margins of the tab the rider is on back to the factory values, and nothing else.
 */
class FitResetTest {
    private val spoiledApps = REauSettings(
        fit = Fit.CROP, cropPercent = 80, cropX = 5, cropY = -7, moveX = 30, moveY = -20, zoom = 130, cover = FrameFit.Cover(top = 10, right = 12),
        density = 200, dashMask = false,
    )

    @Test
    fun `the apps' tab goes back to the factory fit, crop, zoom and margins, and leaves the rest`() {
        val after = FitTab.APPS.restored(spoiledApps)
        assertEquals(REauSettings().appsFit, after.appsFit)
        assertEquals(100, after.zoom)
        assertEquals(FrameFit.Cover.NONE, after.cover)
        assertEquals("the density is not the fit's", 200, after.density)
        assertFalse("the mask is not the fit's", after.dashMask)
        assertEquals("the other tab's fit stays", spoiledApps.navFit, after.navFit)
        assertEquals(spoiledApps.menuFit, after.menuFit)
    }

    @Test
    fun `the navigation tab goes back to its own default and leaves the apps' fit as the rider made it`() {
        val spoiled = spoiledApps.copy(navFit = REauSettings.DEFAULT_NAV_FIT.copy(zoom = 150, cover = FrameFit.Cover(top = 15), cropPercent = 50, moveX = 9))
        val after = FitTab.NAV.restored(spoiled)
        assertEquals(REauSettings.DEFAULT_NAV_FIT, after.navFit)
        assertEquals("the apps' fit stays", spoiled.appsFit, after.appsFit)
        assertEquals(200, after.density)
    }

    @Test
    fun `a fit already at the default is left alone`() {
        for (tab in FitTab.entries) assertEquals(REauSettings(), tab.restored(REauSettings()))
    }

    @Test
    fun `a spoiled fit is really different from the factory one, so the tests above prove something`() {
        assertTrue(spoiledApps.appsFit != REauSettings().appsFit)
    }

    private val panel = File("src/main/java/com/reau/app/ui/FitPanel.kt").readText()

    @Test
    fun `the way back is at the top, before the blocks of the fit, and only asks`() {
        val button = panel.indexOf("\"fit_reset\"")
        assertTrue("there is a button", button > 0)
        assertEquals("one button, not two", 1, Regex("\"fit_reset\"").findAll(panel).count())
        assertTrue("above the density and the tabs", button < panel.indexOf("FitBlocks(tab"))
        assertTrue("it only opens the question: ${panel.substring(button, button + 40)}", panel.substring(button).startsWith("\"fit_reset\", { asking = true })"))
    }

    @Test
    fun `the fit changes only in the answer of the dialog`() {
        val changes = Regex("""change\(tab\.restored\(s\)\)""").findAll(panel).count()
        assertEquals(1, changes)
        val dialog = panel.indexOf("FitResetDialog(tab, onCancel")
        assertTrue("asked before the panel is drawn", dialog in 1 until panel.indexOf("Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_GAP))"))
        assertTrue(panel.indexOf("change(tab.restored(s))") > dialog)
        assertFalse("no reset straight from the button", panel.contains("\"fit_reset\", { change"))
    }

    @Test
    fun `the buttons of the question are at least 48 dp tall`() {
        for (tag in listOf("fit_reset_confirm", "fit_reset_cancel")) {
            val at = panel.indexOf("testTag(\"$tag\")")
            assertTrue(tag, at > 0 && panel.substring(maxOf(0, at - 80), at).contains("heightIn(min = REauSpace.touch)"))
        }
    }

    private fun text(folder: String, name: String): String {
        val strings = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no $name in $folder")
    }

    @Test
    fun `the question is in the three languages, names the tab, and says it cannot be undone`() {
        val words = mapOf(
            "values-pt" to listOf("zoom", "margens", "Não dá para desfazer"),
            "values" to listOf("zoom", "margins", "cannot be undone"),
            "values-es" to listOf("zoom", "márgenes", "No se puede deshacer"),
        )
        for ((folder, said) in words) {
            assertTrue("$folder title", text(folder, "fit_reset_title").endsWith("?"))
            val body = text(folder, "fit_reset_text")
            assertTrue("$folder names the tab: $body", body.contains("%1\$s"))
            for (word in said) assertTrue("$folder says $word: $body", body.contains(word))
            assertTrue("$folder keeps the action's name", text(folder, "fit_reset").isNotBlank())
        }
    }
}
