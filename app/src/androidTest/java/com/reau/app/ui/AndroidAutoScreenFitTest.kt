package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.Fit
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.REauSettings
import com.reau.app.video.PhonePreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole "Encaixe na cúpula" section lives on the "Ver a tela do Android Auto" screen: the fit is chosen on the
 * Android Auto screen, under the picture and the mask, and each change is handed over at once (with the projection
 * on, the dash takes it live). Moved from SettingsScreenTest, unchanged in what they ask; the screen they run on
 * changed (declared).
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoScreenFitTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)

    @Before
    fun setUp() {
        compose.setContent {
            val s = settings
            AndroidAutoScreen(
                onBack = {},
                mask = AaScreenMask(s.dashMask, s.mapping, com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT) { settings = settings.copy(dashMask = it) },
                fit = AaScreenFit(s) { settings = it.sanitized() },
            )
        }
    }

    @After
    fun tearDown() = PhonePreview.hide()

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** "Voltar ao padrão" asks first now (12/11/2026): the answer is Voltar ao padrão in the dialog. */
    private fun confirmReset() {
        compose.onNodeWithTag("fit_reset_confirm").performClick()
        compose.waitForIdle()
    }

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    private fun rect(r: com.reau.app.video.RectPx) = "${r.x},${r.y} ${r.w}×${r.h}"

    @Test
    fun theFitIsChosenAndTheRectanglesItGivesAreShown() {
        compose.onNodeWithTag("fit_WHOLE_VISIBLE").performScrollTo().assertIsSelected()
        text("rects", "Do Android Auto: ${rect(PlanSettings.PLAN.mapping.source)} → no painel: ${rect(PlanSettings.PLAN.mapping.dest)}")
        compose.onNodeWithTag("crop_value").assertDoesNotExist()

        tap("fit_CROP")
        assertEquals(Fit.CROP, settings.fit)
        text("rects", "Do Android Auto: ${rect(settings.mapping.source)} → no painel: ${rect(settings.mapping.dest)}")
    }

    @Test
    fun aCropHasItsSizeInStepsOfOne() {
        tap("fit_CROP")
        assertEquals(Fit.CROP, settings.fit)
        text("crop_value", "60%")
        tap("crop_minus")
        assertEquals(59, settings.cropPercent)
        text("rects", "Do Android Auto: ${rect(settings.mapping.source)} → no painel: ${rect(settings.mapping.dest)}")
        tap("crop_plus")
        tap("crop_plus")
        assertEquals(61, settings.cropPercent)
    }

    @Test
    fun thePictureMovesInStepsOfOneAndGoesNegative() {
        text("move_y_value", "0 px")
        tap("move_y_minus")
        tap("move_y_minus")
        assertEquals("up is negative", -2, settings.moveY)
        text("move_y_value", "-2 px")
        tap("move_x_plus")
        assertEquals(1, settings.moveX)
        text("rects", "Do Android Auto: ${rect(settings.mapping.source)} → no painel: ${rect(settings.mapping.dest)}")
        assertEquals(PlanSettings.PLAN.mapping.dest.x + 1, settings.mapping.dest.x)
    }

    @Test
    fun thePartOfTheCropMovesInPixelsByOneAndByTenAndPastTheEdges() {
        // In pixels: it was % of the slack; changed and declared. At 74% the window is
        // 711×405, centred at x 44, y 37.
        settings = settings.copy(fit = Fit.CROP, cropPercent = 74)
        text("crop_center_x_value", "44 px")
        tap("crop_center_x_plus")
        assertEquals(45, settings.cropX)
        tap("crop_center_x_plus10")
        assertEquals(55, settings.cropX)
        tap("crop_center_y_minus")
        assertEquals(36, settings.cropY)
        tap("crop_center_y_minus10")
        text("crop_center_y_value", "26 px")
        // past the picture's edge, up to all the window but a pixel (FrameFit.cropRange): 799 is the last across
        settings = settings.copy(cropX = 795)
        repeat(2) { tap("crop_center_x_plus10") }
        assertEquals(799, settings.cropX)
    }

    @Test
    fun aNewCropSizeKeepsTheWindowsCentre() {
        settings = settings.copy(fit = Fit.CROP, cropPercent = 74, cropX = 44, cropY = 37)
        tap("crop_minus") // 73%: 721×411, 10 wider and 6 taller
        assertEquals(39 to 34, settings.cropX to settings.cropY)
    }

    @Test
    fun theMoveIsHiddenWhereItDoesNothing() {
        // The crop already fills the dash's frame, so "Mover a imagem" cannot move it
        compose.onNodeWithTag("move_x_value").performScrollTo().assertIsDisplayed()
        tap("fit_CROP")
        compose.onNodeWithTag("move_x_value").assertDoesNotExist()
        compose.onNodeWithTag("move_y_value").assertDoesNotExist()
    }

    @Test
    fun aFitThatDoesNotWorkIsSaidSo() {
        compose.onNodeWithTag("fit_problem").assertDoesNotExist()
        settings = settings.copy(fit = Fit.WHOLE_VISIBLE, marginH = 470).sanitized()
        compose.onNodeWithTag("fit_problem").performScrollTo().assertIsDisplayed().assertTextContains("Este encaixe não serve", substring = true)
    }

    @Test
    fun theMaskSwitchIsThereWithTheFit() {
        // The redesign: the mask's chip sits in the fixed top bar.
        compose.onNodeWithTag("aa_screen_mask_switch").assertIsDisplayed()
        compose.onNodeWithTag("fit_WHOLE_VISIBLE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_FILL_DOME").assertDoesNotExist()
    }

    // ── the navigation apps' own fit (Waze and Google Maps share it) ──────────────────────────────────────────

    @Test
    fun theNavigationTabHasItsOwnFitAtOnceAndNoSwitchForIt() {
        tap("fit_tab_nav")
        compose.onNodeWithTag("fit_WHOLE_VISIBLE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav_fit_own").assertDoesNotExist()
        compose.onNodeWithTag("nav_fit_same").assertDoesNotExist()
    }

    @Test
    fun onTheNavigationTabTheStepsChangeItsFitOnlyAndTheResetIsItsOwn() {
        val apps = settings.appsFit
        tap("fit_tab_nav")
        tap("crop_minus10")
        assertEquals(REauSettings.DEFAULT_NAV_FIT.cropPercent - 10, settings.navFit.cropPercent)
        assertEquals("the apps' fit stays", apps, settings.appsFit)
        tap("fit_reset")
        confirmReset()
        assertEquals("the reset of a tab is that tab's only", REauSettings.DEFAULT_NAV_FIT, settings.navFit)
        assertEquals(apps, settings.appsFit)
    }

    // ── the zoom and the black bands have no counters any more ───────────────────────────────────────────────

    @Test
    fun thereAreNoCountersForTheZoomNorForTheBands() {
        for (tab in listOf("fit_tab_apps", "fit_tab_nav")) {
            tap(tab)
            for (tag in listOf("zoom_value", "cover_top_value", "cover_bottom_value", "cover_left_value", "cover_right_value")) {
                compose.onNodeWithTag(tag).assertDoesNotExist()
            }
        }
    }

    @Test
    fun theResetOfATabTakesItsZoomAndBandsBackToo() {
        settings = settings.copy(zoom = 120, cover = com.reau.app.video.FrameFit.Cover(right = 10))
        tap("fit_reset")
        confirmReset()
        assertEquals(100 to com.reau.app.video.FrameFit.Cover.NONE, settings.zoom to settings.cover)
    }

    // ── the density with the fit, one for the menu and the apps (moved from SettingsScreenTest) ──

    /** The redesign: every counter of the fit steps by one and by ten, the density too. */
    @Test
    fun theDensityGoesUpAndDownByOneAndByTenWithinItsRange() {
        text("density_value", "160")
        tap("density_plus10")
        text("density_value", "170")
        repeat(3) { tap("density_minus") }
        assertEquals(167, settings.density)
        tap("density_minus10")
        assertEquals(157, settings.density)
        settings = settings.copy(density = 80)
        tap("density_minus")
        assertEquals("never below 80", 80, settings.density)
    }

    @Test
    fun theDensityIsOneForTheNavigationAndTheOthers() {
        tap("fit_tab_nav")
        tap("density_plus10")
        assertEquals(170, settings.density)
        tap("fit_tab_apps")
        text("density_value", "170")
    }

    // ── the redesign: steps of ten everywhere, the way back to the defaults, the tab that opened ────────────────────

    @Test
    fun theMoveAndTheCropSizeAlsoStepByTen() {
        tap("move_x_plus10")
        assertEquals(10, settings.moveX)
        tap("move_y_minus10")
        assertEquals(-10, settings.moveY)
        tap("fit_CROP")
        tap("crop_plus10")
        assertEquals(70, settings.cropPercent)
    }

    @Test
    fun theFitGoesBackToTheDefaultOfItsTab() {
        tap("move_x_plus10")
        tap("fit_CROP")
        tap("fit_reset")
        confirmReset()
        assertEquals(com.reau.app.settings.REauSettings().appsFit, settings.appsFit)
    }

    /** The line "Aberta sozinha: o Android Auto mostra um app agora" under the tabs was taken out. */
    @Test
    fun thereIsNoLineUnderTheTabsSayingWhichOpenedByItself() {
        compose.onNodeWithTag("fit_tab_apps").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_tab_hint").assertDoesNotExist()
    }

    // ── "Voltar ao padrão" in sight, asked first (12/11/2026) ───────────────────────────────────────────────────────

    /** The wrong fit to be undone: a crop moved, a zoom and a black band. */
    private fun spoil() {
        settings = settings.copy(
            fit = Fit.CROP, cropPercent = 80, cropX = 5, cropY = 5, moveX = 30, zoom = 120, cover = com.reau.app.video.FrameFit.Cover(right = 10),
        )
    }

    @Test
    fun theWayBackIsInSightAtTheTopWithoutScrollingNorSearching() {
        compose.onNodeWithTag("fit_reset").assertIsDisplayed()
        compose.onNodeWithTag("fit_reset").assertHasClickAction()
    }

    @Test
    fun theWayBackAsksFirstAndCancelingKeepsTheFit() {
        spoil()
        val spoiled = settings
        tap("fit_reset")
        compose.onNodeWithTag("fit_reset_title").assertIsDisplayed()
        compose.onNodeWithTag("fit_reset_text").assertIsDisplayed()
        assertEquals("asking changes nothing", spoiled, settings)
        compose.onNodeWithTag("fit_reset_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fit_reset_title").assertDoesNotExist()
        assertEquals("canceled: the rider's fit stays", spoiled, settings)
    }

    @Test
    fun confirmingTakesTheFitTheCropTheZoomAndTheMarginsBackAndLeavesTheRest() {
        spoil()
        settings = settings.copy(density = 200, dashMask = false)
        val navBefore = settings.navFit
        tap("fit_reset")
        confirmReset()
        compose.onNodeWithTag("fit_reset_title").assertDoesNotExist()
        assertEquals(REauSettings().appsFit, settings.appsFit)
        assertEquals("the density and the mask are not the fit's", 200 to false, settings.density to settings.dashMask)
        assertEquals("the other tab's fit stays", navBefore, settings.navFit)
    }

    @Test
    fun theDialogNamesTheTabItResets() {
        tap("fit_tab_nav")
        tap("fit_reset")
        compose.onNodeWithTag("fit_reset_text").assertTextContains("Navegação", substring = true)
        compose.onNodeWithTag("fit_reset_cancel").performClick()
    }

    /** Every target of the fit's screen is at least 48 dp tall (the counters, the tabs, the modes, the way back and the dialog's buttons). */
    @Test
    fun theTouchTargetsOfTheFitAreAtLeast48Dp() {
        val targets = listOf(
            "fit_reset", "density_minus", "density_plus", "density_minus10", "density_plus10", "move_x_minus", "move_x_plus10",
            "move_y_minus", "move_y_plus10", "fit_tab_nav", "fit_tab_apps", "fit_WHOLE_VISIBLE", "fit_CROP", "aa_screen_mask_switch",
        )
        for (tag in targets) compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        for (tag in listOf("density_minus", "density_plus", "move_x_minus", "move_x_plus")) compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp)
        tap("fit_reset")
        for (tag in listOf("fit_reset_confirm", "fit_reset_cancel")) compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }
}
