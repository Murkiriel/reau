package com.reau.app

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.dash.DashPairing
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ProjectionSides
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long the emulator may take to scan and find its own Wi-Fi. */
private const val FIND_WAIT_MS = 20_000L

/**
 * The app as the rider opens it: the switch starts and stops the real service (its sides left empty here, so
 * nothing reaches for a Wi-Fi or an Android Auto), the settings screen opens and closes, and what is changed in it
 * is kept for the next start.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory

    @Before
    fun setUp() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        // Ligar asks for the location (the Wi-Fi list) while the dash's name is unknown: granted, no dialog.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        SettingsStore.open(context).save(PlanSettings.PLAN)
        DashPairing.open(context).forget()
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        waitFor { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
        SettingsStore.open(context).save(PlanSettings.PLAN)
        DashPairing.open(context).forget()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) { condition() }

    private fun tap(tag: String) {
        // The redesign: "back" sits in the fixed top bar, which never scrolls.
        if (tag == "back") compose.onNodeWithTag(tag).performClick() else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test
    fun theSwitchStartsAndStopsTheProjection() {
        compose.onNodeWithTag("status").assertTextEquals("Parado")
        tap("toggle")

        waitFor { ProjectionService.projection.value.phase == Phase.RUNNING }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("status").assertTextEquals("Conectando") }.isSuccess }
        compose.onNodeWithTag("toggle").assertTextContains("Desligar")

        tap("toggle")
        waitFor { ProjectionService.projection.value.phase == Phase.STOPPED }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("status").assertTextEquals("Parado") }.isSuccess }
        compose.onNodeWithTag("toggle").assertTextContains("Ligar")
    }

    /** Desligar carries the time since the projection started, counted by the service. */
    @Test
    fun desligarCountsTheTimeFromTheStart() {
        assertNull(ProjectionService.startedAt.value)
        tap("toggle")
        waitFor { ProjectionService.projection.value.phase == Phase.RUNNING }
        assertNotNull(ProjectionService.startedAt.value)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertTextContains(":", substring = true) }.isSuccess
        }
        tap("toggle")
        waitFor { ProjectionService.projection.value.phase == Phase.STOPPED }
        waitFor { ProjectionService.startedAt.value == null }
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertDoesNotExist()
    }

    /** The screens follow the rider's "Tema" (always light, always dark, or by the sun). */
    @Test
    fun theScreensFollowTheThemeChosen() {
        fun background(): Int {
            compose.waitForIdle()
            val shot = compose.onRoot().captureToImage().asAndroidBitmap()
            return shot.getPixel(4, shot.height / 2)
        }
        SettingsStore.open(context).save(PlanSettings.PLAN.copy(mapTheme = com.reau.app.settings.MapTheme.DAY))
        compose.activityRule.scenario.recreate()
        assertTrue("light: Marfim", android.graphics.Color.red(background()) > 200)
        SettingsStore.open(context).save(PlanSettings.PLAN.copy(mapTheme = com.reau.app.settings.MapTheme.NIGHT))
        compose.activityRule.scenario.recreate()
        assertTrue("dark: Grafite", android.graphics.Color.red(background()) < 60)
    }

    @Test
    fun theSettingsScreenOpensAndGoesBack() {
        tap("open_settings")
        compose.onNodeWithTag("settings_title").assertIsDisplayed()
        tap("back")
        compose.onNodeWithTag("title").assertIsDisplayed()
    }

    /** The joystick page opens from the settings, a pick is kept, and back returns to the settings. */
    @Test
    fun theJoystickPageOpensFromTheSettingsKeepsAPickAndGoesBack() {
        tap("open_settings")
        tap("open_joystick")
        compose.onNodeWithTag("joystick_title").assertIsDisplayed()
        // The redesign: the media mode's tab, the cross's centre, its action, then the list.
        compose.onNodeWithTag("joystick_mode_media").performClick()
        tap("dpad_center")
        tap("joystick_action_row")
        compose.onNodeWithTag("action_HOME").performScrollTo().performClick()
        compose.waitUntil(5_000) { SettingsStore.open(context).load().joystick == "media.center.once=HOME" }
        tap("back")
        compose.onNodeWithTag("settings_title").assertIsDisplayed()
    }

    @Test
    fun aChangedSettingIsKeptForTheNextStart() {
        // The margins moved to the Android Auto screen and the stream's values and navigation profiles are
        // fixed: the dash's music switch stands in for them here. Declared.
        val before = SettingsStore.open(context).load().dashMusic
        tap("open_settings")
        compose.onNodeWithTag("dash_music").performScrollTo().performClick()
        tap("back")

        assertEquals(!before, SettingsStore.open(context).load().dashMusic)
        tap("open_settings")
        compose.onNodeWithTag("dash_music").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theKnownDashIsShownAndCanBeForgotten() {
        // The Wi-Fi shortcut opens the Wi-Fi's own screen; back returns to the main one.
        DashPairing.open(context).ssid = "RE_TEST_1234"
        compose.activityRule.scenario.recreate()
        tap("tile_wifi")
        compose.onNodeWithTag("wifi_title").assertIsDisplayed()
        compose.onNodeWithTag("ssid_known").performScrollTo().assertTextContains("RE_••••34")
        tap("forget_dash")
        assertEquals("", DashPairing.open(context).ssid)
        tap("back")
        compose.onNodeWithTag("title").assertIsDisplayed()
        compose.onNodeWithTag("tile_wifi_sub", useUnmergedTree = true).assertTextEquals("Nenhum painel conhecido")
    }

    /**
     * The app finds and fills in the dash's Wi-Fi by itself. With the whole name unknown and the Wi-Fi list
     * allowed, REau on screen finds the network by itself and the screen shows it, nobody touching anything. The
     * emulator's own Wi-Fi ("AndroidWifi") stands in for the dash, its name's beginning set to "Andr".
     */
    @Test
    fun theDashsWifiIsFoundAndFilledByItself() {
        assumeTrue("stands on the emulator's own Wi-Fi, \"AndroidWifi\", which a phone does not have", TestDevice.isEmulator)
        DashPairing.open(context).ssidPrefix = "Andr"
        compose.activityRule.scenario.recreate()
        compose.waitUntil(FIND_WAIT_MS) {
            runCatching { compose.onNodeWithTag("tile_wifi_sub", useUnmergedTree = true).assertTextEquals("And••••fi") }.isSuccess
        }
        assertEquals("AndroidWifi", DashPairing.open(context).ssid)
    }

    /** The Wi-Fi's way in is the tile on the main screen (the settings have no shortcut to it any more); the system's Back returns there. */
    @Test
    fun theWifiOpensFromTheMainScreenAndTheSystemBackReturnsToIt() {
        tap("tile_wifi")
        compose.onNodeWithTag("wifi_title").assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.let { compose.runOnUiThread { it.onBackPressed() } }
        compose.onNodeWithTag("title").assertIsDisplayed()
    }

    /**
     * A page opened over the settings and closed leaves them where they were scrolled;
     * leaving the settings and opening them again starts on top.
     */
    @Test
    fun theSettingsKeepTheirPlaceUnderAPage() {
        tap("open_settings")
        tap("open_joystick")
        tap("back")
        compose.onNodeWithTag("open_joystick").assertIsDisplayed()
        tap("back")
        tap("open_settings")
        compose.onNodeWithTag("applies_next_time").assertIsDisplayed()
        compose.onNodeWithTag("open_joystick").assertIsNotDisplayed()
    }

    /** The credits and the terms of use are at the end of "O REau e os seus dados"; back from each returns to it. */
    @Test
    fun theCreditsAndTheTermsOpenFromTheDataPageAndBackReturnsThere() {
        tap("open_about_data")
        compose.onNodeWithTag("about_data_title").assertIsDisplayed()
        tap("open_credits")
        compose.onNodeWithTag("credits_title").assertIsDisplayed()
        tap("back")
        compose.onNodeWithTag("about_data_title").assertIsDisplayed()
        tap("open_terms")
        compose.onNodeWithTag("terms_text").assertIsDisplayed()
        tap("back")
        compose.onNodeWithTag("about_data_title").assertIsDisplayed()
        tap("back")
        compose.onNodeWithTag("title").assertIsDisplayed()
    }

    @Test
    fun reausOwnVersionIsAtTheFoot() {
        compose.onNodeWithTag("reau_version").performScrollTo().assertTextEquals("REau · ${BuildConfig.VERSION_NAME}")
    }

    @Test
    fun theInstalledAndroidAutoIsOnTheScreen() {
        // The emulator's image carries a stub of Android Auto; a phone has the real one.
        val version = com.reau.app.aa.AndroidAuto.installedVersion(context)
        assertNotNull("Android Auto must be visible to the app", version)
        if (TestDevice.isEmulator) assertEquals("1.2.551000-stub", version)
        compose.onNodeWithTag("aa_version").performScrollTo().assertTextEquals("Android Auto instalado · $version")
    }
}
