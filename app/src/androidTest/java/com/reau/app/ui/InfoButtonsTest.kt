package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaVideo
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.settings.AppPermissions
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.REauSettings
import com.reau.app.video.PhonePreview
import com.reau.app.weather.WeatherKeeper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which options carry an (i) (tag `<option>_info`) and which do not, screen by screen. The lists are written out on
 * purpose: an (i) that goes or comes must be a decision here too (docs: AJUSTES_INFO.md). Each screen is checked both
 * ways: the options that must have the (i) have it, the ones that must not do not, and no other (i) is on the screen.
 */
@RunWith(AndroidJUnit4::class)
class InfoButtonsTest {
    @get:Rule val compose = createComposeRule()

    /** An option and the tag of the node that shows it is there (the row's own tag, unless said). */
    private data class Option(val tag: String, val node: String = tag)

    private fun o(tag: String, node: String = tag) = Option(tag, node)

    @After
    fun tearDown() = PhonePreview.hide()

    private fun infoTags(): List<String> = compose.onAllNodes(
        SemanticsMatcher("an (i)") { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(SUFFIX) == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removeSuffix(SUFFIX) }

    private fun exists(node: String) = compose.onNodeWithTag(node, useUnmergedTree = true).assertExists()

    /** [with] have the (i); [without] are on the screen and have none; and the screen has no other (i). */
    private fun check(with: List<Option>, without: List<Option> = emptyList()) {
        for (option in with) {
            exists(option.node)
            exists(option.tag + SUFFIX)
        }
        for (option in without) {
            exists(option.node)
            compose.onNodeWithTag(option.tag + SUFFIX, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals("the (i) of the screen", with.map { it.tag }.sorted(), infoTags().sorted())
    }

    /** Every (i) of [tags] opens its sheet with a text, and "Entendi" closes it. */
    private fun everyInfoOpens(tags: List<String>) {
        for (tag in tags) {
            val info = compose.onNodeWithTag(tag + SUFFIX, useUnmergedTree = true)
            runCatching { info.performScrollTo() } // the ones in a top bar are not in anything that scrolls
            info.performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("info_sheet").assertExists()
            compose.onNodeWithTag("info_sheet_ok").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("info_sheet").assertDoesNotExist()
        }
    }

    // ── Ajustes ────────────────────────────────────────────────────────────────────────────────────────────

    private val settingsWith = listOf(
        o("applies_next_time"), o("map_theme"), o("use_location"), o("dash_phone_status"), o("dash_album_art"), o("dash_route_extras"),
        o("answer_calls"), o("gemini"), o("aa_server_auto"),
        o("fps", "fps_locked"), o("maneuver_fps_profile", "maneuver_fps_profile_locked"), o("speed_fps_profile", "speed_fps_profile_locked"),
        o("card_now"),
        o("ride_log"), o("record_video"), o("record_sessions"), o("detailed_log"), o("log_keep"),
    )

    private val settingsWithout = listOf(
        o("language"), o("dash_caller"), o("dash_music"), o("open_joystick"),
        o("bitrate", "bitrate_locked"), o("bitrate_mode", "bitrate_mode_locked"), o("keyframe", "keyframe_locked"),
        o("card_pick"), o("view_logs"), o("share"), o("delete"),
    )

    private fun showSettings() {
        compose.setContent { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) }
        compose.openDebug()
    }

    @Test
    fun settingsHaveTheirInfoWhereItAdds() {
        showSettings()
        check(settingsWith, settingsWithout)
    }

    @Test
    fun everyInfoOfTheSettingsOpens() {
        showSettings()
        everyInfoOpens(settingsWith.map { it.tag })
    }

    // ── Tela do Android Auto ───────────────────────────────────────────────────────────────────────────────

    private var settings by mutableStateOf(PlanSettings.PLAN)

    private fun showAndroidAuto() {
        compose.setContent {
            val s = settings
            AndroidAutoScreen(
                onBack = {},
                mask = AaScreenMask(s.dashMask, s.mapping, AaVideo.WIDTH, AaVideo.HEIGHT) { settings = settings.copy(dashMask = it) },
                fit = AaScreenFit(s) { settings = it.sanitized() },
            )
        }
    }

    @Test
    fun theAndroidAutoScreenHasItsInfoOnTheModeTheMarginsAndTheStatsText() {
        showAndroidAuto()
        // The apps' tab: the mode and the margins; the stats text, its steps hidden until it is switched on.
        check(
            listOf(o("fit_mode", "fit_FILL_DOME"), o("margin_w", "margin_w_value"), o("margin_h", "margin_h_value"), o("stats_overlay")),
            listOf(o("density", "density_value"), o("move_x", "move_x_value"), o("fit_reset")),
        )
        everyInfoOpens(listOf("fit_mode", "margin_w", "margin_h", "stats_overlay"))
    }

    @Test
    fun theMenuTabAddsTheInfoOfItsOwnFit() {
        showAndroidAuto()
        compose.onNodeWithTag("fit_tab_menu").performScrollTo().performClick()
        compose.waitForIdle()
        // The menu uses the apps' fit until it is given its own: no mode to explain yet.
        check(listOf(o("margin_w", "margin_w_value"), o("margin_h", "margin_h_value"), o("stats_overlay"), o("menu_fit_own")))
        everyInfoOpens(listOf("menu_fit_own"))
        compose.onNodeWithTag("menu_fit_own").performScrollTo().performClick()
        compose.waitForIdle()
        check(listOf(o("fit_mode", "fit_FILL_DOME"), o("margin_w", "margin_w_value"), o("margin_h", "margin_h_value"), o("stats_overlay"), o("menu_fit_own")))
    }

    @Test
    fun theStatsStepsHaveNoInfoOfTheirOwn() {
        settings = settings.copy(statsOverlay = true)
        showAndroidAuto()
        check(
            listOf(o("fit_mode", "fit_FILL_DOME"), o("margin_w", "margin_w_value"), o("margin_h", "margin_h_value"), o("stats_overlay")),
            listOf(o("stats_x", "stats_x_value"), o("stats_y", "stats_y_value"), o("stats_size", "stats_size_value")),
        )
    }

    // ── Android Auto na rede ───────────────────────────────────────────────────────────────────────────────

    private val running = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE)

    private fun showServer(projection: Projection, webView: Boolean = false) {
        settings = settings.copy(webView = webView)
        val state = MainUiState(projection, server = ServerUi(networkOn = webView))
        compose.setContent { ServerScreen(state, settings, { settings = it }) {} }
    }

    @Test
    fun theNetworkServerScreenHasTheInfoOfTheScreenTheSwitchesAndTheSound() {
        showServer(running, webView = true)
        check(listOf(o("server", "server_screen_title"), o("web_view"), o("web_touch"), o("web_audio")), listOf(o("web_warning")))
        everyInfoOpens(listOf("server", "web_view", "web_touch", "web_audio"))
    }

    @Test
    fun withTheProjectionRunningAndTheServerOffTheTouchSwitchIsNotThere() {
        showServer(running)
        check(listOf(o("server", "server_screen_title"), o("web_view"), o("web_audio")))
    }

    @Test
    fun withTheProjectionOffTheLockedSwitchKeepsItsInfo() {
        showServer(Projection())
        check(listOf(o("server", "server_screen_title"), o("web_view", "web_view_locked"), o("web_audio")))
    }

    // ── Wi-Fi do painel ("Dados da moto") ──────────────────────────────────────────────────────────────────

    private fun showWifi(ssid: String) {
        compose.setContent {
            WifiScreen(settings, PairingUi(ssid = ssid, ssidPrefix = "RE_", password = "12345678"), SettingsActions({}, {}, {}, {})) {}
        }
    }

    @Test
    fun theKnownDashHasTheInfoOfForgetAndTheLastReadingOfItsData() {
        showWifi("RE_TEST_1234")
        check(listOf(o("ssid_known"), o("auto_join_wifi"), o("bike_last_read"), o("bike_range")), listOf(o("bike_firmware"), o("bike_safe_mode"), o("bike_abs")))
        everyInfoOpens(listOf("ssid_known", "auto_join_wifi", "bike_last_read", "bike_range"))
    }

    @Test
    fun withNoKnownDashThereIsNoInfoOnTheLineThatSaysSo() {
        showWifi("")
        check(listOf(o("auto_join_wifi"), o("bike_last_read"), o("bike_range")), listOf(o("ssid_known")))
    }

    // ── Clima ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theWeatherScreenHasTheInfoOfItsSwitchOffAndOn() {
        WeatherKeeper.clear(InstrumentationRegistry.getInstrumentation().targetContext)
        var on by mutableStateOf(false)
        compose.setContent { WeatherScreen(on, { on = it }) {} }
        check(listOf(o("dash_weather")))
        on = true
        compose.waitForIdle()
        check(listOf(o("dash_weather")), listOf(o("weather_state"), o("weather_sent")))
        everyInfoOpens(listOf("dash_weather"))
    }

    // ── Screens with no (i): the text on them is the explanation ──────────────────────────────────────────

    @Test
    fun theMainScreenHasNoInfo() {
        compose.setContent { MainScreen(MainUiState(), {}, {}, {}) }
        check(emptyList(), listOf(o("main_scroll")))
    }

    @Test
    fun theMainScreenWithItsWarningCardsHasNoInfo() {
        val state = MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF), vpnHoldsREau = true)
        compose.setContent { MainScreen(state, {}, {}, {}) }
        check(emptyList(), listOf(o("main_scroll")))
    }

    @Test
    fun theJoystickPageHasNoInfoOnAnyOfItsThreeTabs() {
        compose.setContent { JoystickScreen(REauSettings(), {}, {}) }
        check(emptyList(), listOf(o("joystick_calls"), o("reset_joystick")))
        compose.onNodeWithTag("joystick_mode_media").performClick()
        check(emptyList(), listOf(o("reset_joystick")))
        compose.onNodeWithTag("joystick_mode_call").performClick()
        compose.waitForIdle()
        check(emptyList(), listOf(o("joystick_selected")))
    }

    @Test
    fun thePermissionsPageHasNoInfoItsRowsOpenTheirOwnWhy() {
        val rows = AppPermissions.rows(
            requested = listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"),
            granted = { it == "android.permission.INTERNET" },
            accessibilityOn = false,
            batteryExempt = true,
        )
        compose.setContent { PermissionsScreen(rows, PermissionsActions(), sdk = 34) }
        check(emptyList(), listOf(o("permission_android.permission.INTERNET")))
    }

    @Test
    fun theAboutDataPageHasNoInfoItsBlocksUnfoldTheText() {
        compose.setContent { AboutDataScreen({}, {}, {}) }
        check(emptyList(), listOf(o("about_summary"), o("open_terms"), o("open_credits")))
    }

    @Test
    fun theCreditsAndTheTermsAreReadOnlyWithNoInfo() {
        compose.setContent { CreditsScreen(onBack = {}, onCopy = {}) }
        check(emptyList())
    }

    @Test
    fun theTermsHaveNoInfo() {
        compose.setContent { TermsScreen(false, {}, {}, {}) }
        compose.onNodeWithText("Termos de Uso", substring = true, useUnmergedTree = true).assertExists()
        check(emptyList())
    }

    private companion object {
        const val SUFFIX = "_info"
    }
}
