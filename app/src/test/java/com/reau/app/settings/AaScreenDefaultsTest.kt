package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.video.ButtonOverlay
import com.reau.app.video.FrameFit
import com.reau.app.video.StatsOverlay
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Tela do Android Auto": every control of the screen starts at the value the owner's own phone had (28/10/2026), on a new install: read from an
 * empty store, kept by [REauSettings.sanitized] (in a debug build and in a release) and by saving with nothing changed. A setting the screen
 * uses that is not in the table fails the guard at the end, so a new control cannot go without its default.
 */
class AaScreenDefaultsTest {
    private val none = FrameFit.Cover.NONE

    /** The values of the owner's phone (the keys it had stored for this screen); what it never changed is the default of before, marked. */
    private fun table(s: REauSettings): Map<String, Any?> = mapOf(
        "density" to s.density, "marginW" to s.marginW, "marginH" to s.marginH,
        // The "Navegação" tab (Waze, Google Maps): crop of the centre, 74%.
        "navFit" to s.navFit,
        // The "Outros" tab: the whole picture, a crop of 64% placed at (-34, 54); the moves were never stored (20 and 0 as before).
        "appsFit" to s.appsFit,
        // The menu's own fit, on.
        "menuFit" to s.menuFit, "menuFitKept" to s.menuFitKept,
        "dashMask" to s.dashMask,
        "statsOverlay" to s.statsOverlay, "statsX" to s.statsX, "statsY" to s.statsY, "statsSize" to s.statsSize,
        "buttonOverlay" to s.buttonOverlay, "buttonX" to s.buttonX, "buttonY" to s.buttonY, "buttonSize" to s.buttonSize,
        "maneuverRing" to s.maneuverRing,
        "infoRing" to s.infoRing, "infoRingData" to s.infoRingData, "infoRingCarousel" to s.infoRingCarousel,
        "infoRingSeconds" to s.infoRingSeconds, "infoRingInPlace" to s.infoRingInPlace,
        "dashWeather" to s.dashWeather, "useLocation" to s.useLocation,
    )

    private val expected: Map<String, Any?> = mapOf(
        "density" to 180, "marginW" to 0, "marginH" to 0,
        "navFit" to FitParams(Fit.CROP, 74, 90, 62, 0, 0, 100, none),
        "appsFit" to FitParams(Fit.WHOLE_VISIBLE, 64, -34, 54, 20, 0, 100, none),
        "menuFit" to FitParams(Fit.WHOLE_VISIBLE, 90, 32, 20, 20, 0, 100, none),
        "menuFitKept" to FitParams(Fit.WHOLE_VISIBLE, 90, 32, 20, 20, 0, 100, none),
        "dashMask" to true,
        "statsOverlay" to false, "statsX" to 160, "statsY" to 44, "statsSize" to 22,
        "buttonOverlay" to false, "buttonX" to 170, "buttonY" to 96, "buttonSize" to 32,
        "maneuverRing" to false,
        "infoRing" to false, "infoRingData" to 2047, "infoRingCarousel" to true, "infoRingSeconds" to 7, "infoRingInPlace" to false,
        "dashWeather" to true, "useLocation" to true,
    )

    @Test
    fun `a new install starts with the values of the owner's phone, as written, as read from an empty store and as sanitized`() {
        assertEquals(expected, table(REauSettings()))
        assertEquals("read from an empty store", expected, table(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load()))
        assertEquals("sanitized in a debug build", expected, table(REauSettings().sanitized(debugBuild = true)))
        assertEquals("sanitized in a release", expected, table(REauSettings().sanitized(debugBuild = false)))
    }

    @Test
    fun `saving with nothing changed keeps every one of them`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val first = SettingsStore(prefs).load()
        SettingsStore(prefs).save(first)
        assertEquals(expected, table(SettingsStore(prefs).load()))
        assertEquals(first, SettingsStore(prefs).load())
    }

    @Test
    fun `the stats and button numbers are the overlays' own defaults, and the apps' reset goes back to the owner's fit`() {
        assertEquals(listOf(160, 44, 22), listOf(StatsOverlay.DEFAULT_X, StatsOverlay.DEFAULT_Y, StatsOverlay.DEFAULT_SIZE))
        assertEquals(listOf(170, 96, 32), listOf(ButtonOverlay.DEFAULT_X, ButtonOverlay.DEFAULT_Y, ButtonOverlay.DEFAULT_SIZE))
        val changed = REauSettings().withAppsFit(REauSettings().appsFit.copy(cropPercent = 80, cropX = 5, cropY = 5))
        assertEquals(REauSettings().appsFit, REauSettings().withAppsFit(changed.appsFit.copy(cropPercent = 64, cropX = -34, cropY = 54)).appsFit)
    }

    @Test
    fun `the two texts over the video are a debug build's, a release forces them off and a debug build keeps them`() {
        val on = REauSettings(statsOverlay = true, buttonOverlay = true)
        assertEquals(true to true, on.sanitized(debugBuild = true).let { it.statsOverlay to it.buttonOverlay })
        assertEquals(false to false, on.sanitized(debugBuild = false).let { it.statsOverlay to it.buttonOverlay })
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(on)
        assertEquals("stored on by a debug build, still forced off by a release (sanitized)", false, on.sanitized(debugBuild = false).statsOverlay)
    }

    /** Every setting the screen's code reads or writes is in the table: a control added without its default fails here. */
    @Test
    fun `every setting the Android Auto screen uses is covered by the table`() {
        val ui = File("src/main/java/com/reau/app/ui")
        val sources = listOf("FitPanel.kt", "AaScreenFit.kt", "AaScreenMask.kt").joinToString("\n") { File(ui, it).readText() }
        val members = (REauSettings::class.java.declaredFields.map { it.name } + REauSettings::class.java.methods.map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercase() } }).toSet()
        val used = (Regex("""\bs\.([a-zA-Z]+)""").findAll(sources).map { it.groupValues[1] } +
            Regex("""\bcopy\(([a-zA-Z]+) =""").findAll(sources).map { it.groupValues[1] }).filter { it in members }.toSet()
        // Derived, not stored: the fits' rectangles and the helpers; the fits themselves are in the table.
        // The apps' fit is flattened in the settings (fit, crop, place, move): the table covers it whole as "appsFit".
        val derived = setOf("mapping", "navMapping", "content", "withAppsFit", "copy", "fit", "cropPercent", "cropX", "cropY", "moveX", "moveY")
        val missing = used - expected.keys - derived
        assertEquals("settings the screen uses and the table does not cover: $missing", emptySet<String>(), missing)
        assertTrue("the guard found only $used", used.size >= 10)
    }
}
