package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the redesign ("Ajustes") does to the settings: the groups,
 * "Depuração" folded, segments for the choices, and every long text of today whole behind its (i)
 * (no setting goes, none of its words are cut).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenLookTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(REauSettings())

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {})) }
    }

    @Test
    fun theGroupsAreThereInTheirOrder() {
        for (tag in listOf("group_map", "group_on_dash", "group_joystick", "group_android_auto", "group_video_profiles", "group_card", "debug")) {
            compose.onNodeWithTag(tag).performScrollTo()
        }
        compose.onNodeWithTag("open_credits").assertDoesNotExist() // the credits and the terms are under "O REau e os seus dados" now
    }

    @Test
    fun debugStartsFoldedAndOpensOnATap() {
        compose.onNodeWithTag("ride_log").assertDoesNotExist()
        compose.openDebug()
        compose.onNodeWithTag("ride_log").performScrollTo()
        compose.onNodeWithTag("stats_overlay").assertDoesNotExist() // moved to the "Tela do Android Auto"
        compose.onNodeWithTag("debug").performScrollTo().performClick()
        compose.onNodeWithTag("ride_log").assertDoesNotExist()
    }

    @Test
    fun everyLongTextOfTodayIsWholeBehindItsInfo() {
        compose.infoSays("use_location", "nunca vai ao registro nem sai do celular")
        compose.infoSays("answer_calls", "WhatsApp e outros apps de voz")
        compose.infoSays("gemini", "Pede a permissão do microfone")
        compose.openDebug()
        compose.infoSays("detailed_log", "cada pacote do painel")
        compose.infoSays("log_keep", "vale na hora ao escolher")
    }
}
