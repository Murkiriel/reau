package com.reau.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.ui.theme.LocalREauNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the redesign ("Tela principal") adds to the main screen:
 * the time on Desligar, the shortcuts and what an unavailable one says.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenLookTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun show(state: MainUiState, now: Long = 0L, night: Boolean = true) {
        val extras = MainExtras(
            onShowAndroidAuto = { opened += "aa_screen" },
            onOpenPermissions = { opened += "permissions" },
            onOpenAboutData = { opened += "about_data" },
            onOpenWifi = { opened += "wifi" },
            onOpenWeather = { opened += "weather" },
        )
        compose.setContent {
            CompositionLocalProvider(LocalREauNight provides night) {
                MainScreen(state, {}, { opened += "aa_settings" }, { opened += "settings" }, extras, clock = { now })
            }
        }
    }

    private fun tile(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    private fun tileSub(tag: String, expected: String) =
        compose.onNodeWithTag("${tag}_sub", useUnmergedTree = true).assertTextEquals(expected)

    @Test
    fun desligarShowsTheTimeSinceTheProjectionStarted() {
        val running = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY)
        show(MainUiState(running, startedAtMs = 1_000L), now = 253_000L)
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertTextEquals("04:12")
    }

    @Test
    fun stoppedThereIsNoTime() {
        show(MainUiState(Projection()), now = 253_000L)
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun stoppedTheAndroidAutoScreenSaysWhyAndDoesNotOpen() {
        show(MainUiState(Projection()))
        tileSub("show_aa_screen", "Ligue a projeção")
        tile("show_aa_screen").performClick()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithTag("snackbar").assertIsDisplayed()
        compose.onNodeWithText("Tela do Android Auto: ligue a projeção.").assertIsDisplayed()
    }

    @Test
    fun runningTheAndroidAutoScreenOpensEvenWithoutTheDash() {
        show(MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.CONNECTING)))
        tileSub("show_aa_screen", "Ver e encaixar a imagem")
        tile("show_aa_screen").performClick()
        assertEquals(listOf("aa_screen"), opened)
    }

    @Test
    fun inTheCardModeTheAndroidAutoScreenSaysSo() {
        show(MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY, card = true)))
        tileSub("show_aa_screen", "Fora do modo wallpaper")
    }

    @Test
    fun theWifiShortcutNamesTheKnownDashAndOpensTheWifiScreen() {
        // Its own screen, no longer the settings.
        show(MainUiState(Projection(), dashName = "RE_AB12_345678"))
        tileSub("tile_wifi", "RE_••••78") // the name hidden on the main screen
        tile("tile_wifi").performClick()
        assertEquals(listOf("wifi"), opened)
    }

    @Test
    fun withoutAKnownDashTheWifiShortcutSaysSo() {
        show(MainUiState(Projection(), dashName = ""))
        tileSub("tile_wifi", "Nenhum painel conhecido")
    }

    @Test
    fun thePermissionsShortcutCountsWhatIsMissing() {
        show(MainUiState(Projection(), permissionsMissing = 0))
        tileSub("open_permissions", "Todas liberadas")
        tile("open_permissions").performClick()
        tile("open_about_data").performClick()
        tile("aa_settings_shortcut").performClick()
        assertEquals(listOf("permissions", "about_data", "aa_settings"), opened)
    }

    @Test
    fun reausOwnVersionIsUnderAndroidAutos() {
        show(MainUiState(Projection(), androidAutoVersion = "17.9.664004-release", reauVersion = "26.01.01-12.00"))
        compose.onNodeWithTag("reau_version").performScrollTo().assertTextEquals("REau · 26.01.01-12.00")
        val aa = compose.onNodeWithTag("aa_version").getUnclippedBoundsInRoot()
        val reau = compose.onNodeWithTag("reau_version").getUnclippedBoundsInRoot()
        assertTrue("REau's line under Android Auto's: $aa / $reau", reau.top >= aa.bottom)
    }

    /**
     * Stopped and with nothing wrong, every shortcut fits without scrolling. The weather
     * has a shortcut of its own, a seventh, in a line of its own: the first six
     * are all in view, and the weather's is a scroll away.
     */
    private fun shortcutsFitAndTheWeatherIsBelow(night: Boolean) {
        show(MainUiState(Projection(), androidAutoVersion = "17.9.664004-release", dashName = "RE_AB12_345678"), night = night)
        for (tag in listOf("show_aa_screen", "tile_wifi", "aa_settings_shortcut", "open_settings", "open_permissions", "open_about_data")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("tile_weather").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reau_version").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stoppedShortcutsFitWithoutScrollingDark() = shortcutsFitAndTheWeatherIsBelow(night = true)

    @Test
    fun stoppedShortcutsFitWithoutScrollingLight() = shortcutsFitAndTheWeatherIsBelow(night = false)

    @Test
    fun theWeatherShortcutSaysItIsOffAndOpensItsScreen() {
        show(MainUiState(Projection()))
        tileSub("tile_weather", "Desligado")
        tile("tile_weather").performClick()
        assertEquals(listOf("weather"), opened)
    }

    /** A last shortcut with no pair takes the whole line; two shortcuts of a line are side by side again. */
    @Test
    fun theLastShortcutAloneTakesTheWholeLineAndPairsStayHalfAndHalf() {
        show(MainUiState(Projection()))
        val alone = compose.onNodeWithTag("tile_weather").performScrollTo().getUnclippedBoundsInRoot()
        val paired = compose.onNodeWithTag("open_permissions").performScrollTo().getUnclippedBoundsInRoot()
        val width = { b: androidx.compose.ui.unit.DpRect -> (b.right - b.left).value }
        assertTrue("alone ${width(alone)} dp, paired ${width(paired)} dp", width(alone) > width(paired) * 1.8f)
    }

    @Test
    fun twoMissingPermissionsAreSaid() {
        show(MainUiState(Projection(), permissionsMissing = 2))
        tileSub("open_permissions", "Faltam 2")
    }
}
