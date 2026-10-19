package com.reau.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.service.Projection
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens: the permissions page and "O REau e os seus dados" each with its own
 * button on the main screen (no longer in the settings); and out of the settings, the choices that are now fixed (what
 * to ask Android Auto: 800×480; the video clock: 0%; the cluster experiment; touching Android Auto from the phone: on)
 * and the leftover note of the dome fit.
 */
@RunWith(AndroidJUnit4::class)
class ScreensCleanupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theMainScreenHasThePermissionsAndTheDataButtons() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MainScreen(
                MainUiState(Projection()),
                onToggle = {},
                onOpenAndroidAutoSettings = {},
                onOpenSettings = {},
                extras = MainExtras(onOpenPermissions = { opened += "permissions" }, onOpenAboutData = { opened += "about" }),
            )
        }
        compose.onNodeWithTag("open_permissions").performScrollTo().assertTextContains("Permissões", substring = true).performClick()
        compose.onNodeWithTag("open_about_data").performScrollTo().assertTextContains("Seus dados", substring = true).performClick()
        assertEquals(listOf("permissions", "about"), opened)
    }

    @Test
    fun theSettingsNoLongerOfferWhatIsFixedNorThePagesThatMoved() {
        compose.setContent { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) }
        compose.onNodeWithTag("settings_title").assertTextContains("Ajustes")
        for (tag in listOf(
            "resolution_LANDSCAPE_800x480", "resolution_LANDSCAPE_1280x720", "clock_0", "clock_5", "cluster_OFF", "cluster_A",
            "phone_touch", "fit_moved", "open_permissions", "open_about_data", "open_credits", "open_terms",
        )) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }
}
