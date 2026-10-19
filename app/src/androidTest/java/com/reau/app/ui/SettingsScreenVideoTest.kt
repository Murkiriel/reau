package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stream's frame ceiling, bitrate, mode and keyframe are fixed (two profiles), shown and
 * not to be touched. Before, each was a choice, taken at once.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenVideoTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {})) }
    }

    @Test
    fun theFourStreamValuesAreShownAndLocked() {
        for (tag in listOf("fps", "bitrate", "bitrate_mode", "keyframe")) {
            compose.onNodeWithTag("${tag}_locked").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("bitrate_locked").assertTextContains("400 kbps", substring = true)
        compose.onNodeWithTag("keyframe_locked").assertTextContains("1 s", substring = true)
        for (tag in listOf("bitrate_plus", "bitrate_minus", "bitrate_vbr", "bitrate_cbr", "keyframe_1", "keyframe_2", "keyframe_auto")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    @Test
    fun thereIsNoReductionChoiceAnyMore() {
        for (tag in listOf("reduction", "reduction_default", "reduction_soft", "reduction_soft_sharp", "reduction_cubic")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }
}
