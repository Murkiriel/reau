package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    private fun rect(r: com.reau.app.video.RectPx) = "${r.x},${r.y} ${r.w}×${r.h}"

    @Test
    fun theFitIsChosenAndTheRectanglesItGivesAreShown() {
        compose.onNodeWithTag("fit_FILL_DOME").performScrollTo().assertIsSelected()
        text("rects", "Do Android Auto: ${rect(PlanSettings.PLAN.mapping.source)} → no painel: ${rect(PlanSettings.PLAN.mapping.dest)}")
        compose.onNodeWithTag("crop_value").assertDoesNotExist()

        tap("fit_WHOLE_VISIBLE")
        assertEquals(Fit.WHOLE_VISIBLE, settings.fit)
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
        compose.onNodeWithTag("fit_FILL_DOME").performScrollTo().assertIsDisplayed()
    }

    // ── the app menu's own fit ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theMenuTabStartsWithTheAppsFitAndItsOwnIsSwitchedOn() {
        tap("fit_tab_menu")
        compose.onNodeWithTag("menu_fit_same").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_FILL_DOME").assertDoesNotExist()
        tap("menu_fit_own")
        assertEquals("the menu's own default, not a copy of the apps' fit", REauSettings.DEFAULT_MENU_FIT, settings.menuFit)
        compose.onNodeWithTag("fit_FILL_DOME").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun onTheMenuTabTheStepsChangeTheMenusFitOnly() {
        tap("fit_tab_menu")
        tap("menu_fit_own")
        val apps = settings.appsFit
        val before = settings.menuFit!!
        tap("fit_CROP")
        tap("crop_center_x_plus")
        assertEquals(Fit.CROP, settings.menuFit?.fit)
        assertEquals("one step to the right of where its own fit was", before.cropX!! + 1, settings.menuFit?.cropX)
        assertEquals("the apps' fit stays", apps, settings.appsFit)
        text("rects", "Do Android Auto: ${rect(settings.menuMapping.source)} → no painel: ${rect(settings.menuMapping.dest)}")
        tap("fit_tab_apps")
        tap("move_x_plus")
        assertEquals(1, settings.moveX)
        assertEquals("the menu's stays", before.moveX, settings.menuFit?.moveX)
    }

    @Test
    fun switchingTheMenusOwnFitOffGoesBackToTheApps() {
        tap("fit_tab_menu")
        tap("menu_fit_own")
        tap("menu_fit_own")
        assertEquals(null, settings.menuFit)
    }

    /** A stray tap on the switch and back must not change the menu's fit, whatever the apps' fit is by then. */
    @Test
    fun switchingTheMenusOwnFitOffAndOnGivesBackTheOneItHad() {
        tap("fit_tab_menu")
        tap("menu_fit_own")
        tap("crop_center_x_plus")
        val own = settings.menuFit!!
        tap("menu_fit_own")
        tap("menu_fit_own")
        assertEquals(own, settings.menuFit)
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
    fun theDensityIsOneForTheMenuAndTheApps() {
        tap("fit_tab_menu")
        tap("menu_fit_own")
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
        assertEquals(com.reau.app.settings.REauSettings().appsFit, settings.appsFit)
    }

    /** The line "Aberta sozinha: o Android Auto mostra um app agora" under the tabs was taken out. */
    @Test
    fun thereIsNoLineUnderTheTabsSayingWhichOpenedByItself() {
        compose.onNodeWithTag("fit_tab_apps").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_tab_hint").assertDoesNotExist()
    }

    // ── on the same screen as Android Auto's picture: the margins asked of Android
    // Auto, by one and by ten (they were in the settings, by ten), never below zero ──────────────────────────────────

    @Test
    fun theMarginsGoUpAndDownByOneAndByTenAndNeverBelowZero() {
        text("margin_w_value", "0 px")
        tap("margin_w_plus10")
        tap("margin_w_plus10")
        tap("margin_w_plus")
        tap("margin_h_plus10")
        assertEquals(21 to 10, settings.marginW to settings.marginH)
        text("margin_w_value", "21 px")
        tap("margin_h_minus10")
        tap("margin_h_minus")
        assertEquals("never below zero", 0, settings.marginH)
    }
}
