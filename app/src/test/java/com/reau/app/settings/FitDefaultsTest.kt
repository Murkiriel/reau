package com.reau.app.settings

import com.reau.app.aa.AaVideo
import com.reau.app.ui.FitTab
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The factory fits by the density asked of Android Auto (13/11/2026). What Android Auto draws is what REau asks for (always 800×480, the
 * density and the margins of the settings), not the phone's screen, so the density asked is the key. One row, the measured one: the fits
 * of the owner's phone at density 180, to the number. No other density has a row (nobody measured one), and every density still gets the
 * fits of today.
 */
class FitDefaultsTest {
    private val apps = FitParams(Fit.WHOLE_VISIBLE, 64, -34, 54, 20, 0)
    private val nav = FitParams(Fit.CROP, 74, 90, 62, 0, 0)
    private val menu = FitParams(Fit.WHOLE_VISIBLE, 90, 32, 20, 20, 0)

    @Test
    fun `the frame asked of Android Auto is the same on every phone, 800 by 480 with the density and the margins of the settings`() {
        assertEquals(800 to 480, AaVideo.WIDTH to AaVideo.HEIGHT)
        val discovery = File("src/main/java/com/reau/app/aa/ServiceDiscoveryResponse.kt").readText()
        assertTrue(discovery.contains("codecResolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480"))
        assertTrue(discovery.contains("density = config.density"))
        assertTrue(discovery.contains("marginWidth = config.margins.marginW"))
        val asked = REauSettings(density = 210, marginW = 40, marginH = 20).aaConfig
        assertEquals(210, asked.density)
        assertEquals(40 to 20, asked.margins.marginW to asked.margins.marginH)
        // No screen of the phone in what is asked: the request is made of the settings alone.
        for (word in listOf("DisplayMetrics", "densityDpi", "LocalDensity", "resources.displayMetrics")) {
            assertTrue("$word in the request", !discovery.contains(word))
        }
    }

    @Test
    fun `the one measured row is the density of a new install, with the fits of the owner's phone to the number`() {
        assertEquals(180, FitDefaults.MEASURED_DENSITY)
        assertEquals(FitDefaults.MEASURED_DENSITY, REauSettings().density)
        assertEquals(setOf(180), FitDefaults.densities)
        assertEquals(FitDefaults.Fits(apps, nav, menu), FitDefaults.measured(180))
    }

    @Test
    fun `a new install still starts with those fits`() {
        val fresh = REauSettings()
        assertEquals(apps, fresh.appsFit)
        assertEquals(nav, fresh.navFit)
        assertEquals(menu, fresh.menuFit)
        assertEquals(menu, fresh.menuFitKept)
        assertEquals(0 to 0, fresh.marginW to fresh.marginH)
        assertEquals(FitDefaults.forDensity(fresh.density), FitDefaults.Fits(fresh.appsFit, fresh.navFit, fresh.menuFitKept))
    }

    @Test
    fun `no other density has a row, no number was made up`() {
        for (density in REauSettings.DENSITY) {
            if (density != 180) assertNull("density $density", FitDefaults.measured(density))
        }
        assertNull(FitDefaults.measured(0))
    }

    @Test
    fun `a density with no row gets the measured row, which is what it got before the table`() {
        val measured = FitDefaults.forDensity(180)
        for (density in listOf(80, 120, 160, 170, 190, 200, 240, 320, 640)) assertSame("density $density", measured, FitDefaults.forDensity(density))
    }

    @Test
    fun `Voltar ao padrão gives the same fits at every density, the ones of the table`() {
        for (density in listOf(120, 180, 200, 320)) {
            val spoiled = REauSettings(density = density, cropPercent = 80, moveX = 7, navFit = nav.copy(cropX = 0, cropPercent = 50))
            assertEquals(FitDefaults.forDensity(density).apps, FitTab.APPS.restored(spoiled).appsFit)
            assertEquals(FitDefaults.forDensity(density).nav, FitTab.NAV.restored(spoiled).navFit)
            assertEquals("the density is left alone", density, FitTab.NAV.restored(spoiled).density)
        }
    }
}
