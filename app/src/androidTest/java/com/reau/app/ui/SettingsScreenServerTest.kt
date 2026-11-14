package com.reau.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings have no switch for the server and no group for it (0.3.0). */
@RunWith(AndroidJUnit4::class)
class SettingsScreenServerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thereIsNoServerSwitchAndNoGroupForIt() {
        compose.setContent { SettingsScreen(PlanSettings.PLAN, SettingsActions({}, {}, {}, {})) }
        for (tag in listOf("group_android_auto", "aa_server_auto")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }
}
