package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.AppLanguage
import com.reau.app.settings.Fit
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings screen: every control, what it shows and what it changes. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)
    private var back = 0
    private var joystick = 0
    private var language: AppLanguage? = null

    @Before
    fun setUp() {
        compose.setContent {
            SettingsScreen(
                settings,
                // Kept as handed over: the screen itself must hand every change over sanitized.
                SettingsActions({ settings = it }, {}, {}, { back++ }, onLanguage = { language = it }),
                onOpenJoystick = { joystick++ },
            )
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    // ── the language ───────────────────────────────────────────────────────────────────────────────────────

    /** The run speaks Portuguese (KeepAppStateRunner); the language names are the languages' own, "Auto" the app's. */
    @Test
    fun theLanguageRowOffersAutomaticPortugueseAndEnglishAndHandsTheChoiceOver() {
        compose.onNodeWithTag("group_language").performScrollTo().assertIsDisplayed()
        text("language_AUTO", "Auto")
        text("language_PT", "Português")
        text("language_EN", "English")
        assertEquals(null, language)
        tap("language_EN")
        assertEquals(AppLanguage.EN, language)
        tap("language_AUTO")
        assertEquals(AppLanguage.AUTO, language)
    }

    // ── the map and the location ───────────────────────────────────────────────────────────────────────────

    /** No Wi-Fi shortcut, no bubble choice; the location sits right under the theme. */
    @Test
    fun theLocationSitsUnderTheThemeAndTheWifiAndBubbleAreGone() {
        compose.onNodeWithTag("open_wifi").assertDoesNotExist()
        compose.onNodeWithTag("group_dash").assertDoesNotExist()
        compose.onNodeWithTag("idle_card").assertDoesNotExist()
        compose.onNodeWithTag("use_location").performScrollTo().assertIsDisplayed()
        val theme = compose.onNodeWithTag("map_theme").fetchSemanticsNode().boundsInRoot
        val location = compose.onNodeWithTag("use_location").fetchSemanticsNode().boundsInRoot
        assertTrue(location.top >= theme.bottom)
    }

    /**
     * The density moved to the Android Auto screen with the fit, and
     * the margins too (their tests went with them); the margins were then taken out of the app altogether.
     */
    @Test
    fun theDensityAndTheMarginsLiveOnTheAndroidAutoScreenNow() {
        compose.onNodeWithTag("density_value").assertDoesNotExist()
        compose.onNodeWithTag("margin_w_value").assertDoesNotExist()
        compose.onNodeWithTag("margin_h_value").assertDoesNotExist()
        tap("profile_details") // the stream's numbers are behind "Detalhes" (12/11/2026)
        compose.onNodeWithTag("fps_locked").performScrollTo().assertIsDisplayed()
    }

    // ── the fit ────────────────────────────────────────────────────────────────────────────────────────────

    // ── pace ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFrameCeilingIsShownAndCannotBeChanged() {
        tap("profile_details") // behind "Detalhes" (12/11/2026)
        compose.onNodeWithTag("fps_locked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fps_plus").assertDoesNotExist()
        compose.onNodeWithTag("fps_minus").assertDoesNotExist()
    }

    // ── debug ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDebugRowsComeInTheOwnersOrderTheDetailedLogRightUnderTheRideLog() {
        compose.openDebug()
        val order = listOf("ride_log", "detailed_log", "record_video", "record_sessions", "view_logs")
        compose.onNodeWithTag(order.last()).performScrollTo()
        val tops = order.map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals("top to bottom: $order", tops.sorted(), tops)
        for (gone in listOf("faster_menu", "aa_single_version_request", "diag_link_only", "diag_no_dscp")) compose.onNodeWithTag(gone).assertDoesNotExist()
    }

    @Test
    fun theTwoRecordingsAreSwitchedOnAndOff() {
        compose.openDebug()
        compose.onNodeWithTag("record_video").performScrollTo().assertIsOff()
        compose.onNodeWithTag("record_sessions").performScrollTo().assertIsOff()
        compose.onNodeWithTag("record_video").performScrollTo().tapSwitch()
        compose.onNodeWithTag("record_sessions").performScrollTo().tapSwitch()
        assertTrue(settings.recordVideo && settings.recordSessions)
        compose.onNodeWithTag("record_video").performScrollTo().assertIsOn()
        compose.onNodeWithTag("record_sessions").performScrollTo().tapSwitch()
        assertEquals(false, settings.recordSessions)
    }

    // ── the screen ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun itSaysTheSettingsCountFromTheNextStartAndGoesBack() {
        compose.onNodeWithTag("applies_next_time").performScrollTo().assertIsDisplayed()
        compose.infoSays("applies_next_time", "Densidade também vale")
        compose.onNodeWithTag("back").performClick() // the fixed top bar
        assertEquals(1, back)
    }

    private fun rect(r: com.reau.app.video.RectPx) = "${r.x},${r.y} ${r.w}×${r.h}"

    /** The joystick page opens from the settings. */
    @Test
    fun theJoystickPageOpensFromTheSettings() {
        tap("open_joystick")
        assertEquals(1, joystick)
    }

}
