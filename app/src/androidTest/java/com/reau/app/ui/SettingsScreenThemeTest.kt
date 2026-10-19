package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.MapTheme
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The map theme in the settings: by the sun, always day or always night. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenThemeTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {})) }
    }

    @Test
    fun byTheClockUntilTheRiderChooses() {
        compose.onNodeWithTag("theme_AUTO").performScrollTo().assertIsSelected()
        // No position, the time zone.
        // The theme of REau and of Android Auto, by the sun.
        compose.onNodeWithTag("map_theme").assertTextContains("pelo sol", substring = true)
        compose.infoSays("map_theme", "posição do celular", "fuso horário")
        compose.onNodeWithTag("theme_NIGHT").performScrollTo().assertIsNotSelected()
    }

    @Test
    fun eachOfTheThreeCanBeChosen() {
        compose.onNodeWithTag("theme_NIGHT").performScrollTo().performClick()
        assertEquals(MapTheme.NIGHT, settings.mapTheme)
        compose.onNodeWithTag("theme_DAY").performScrollTo().performClick()
        assertEquals(MapTheme.DAY, settings.mapTheme)
        compose.onNodeWithTag("theme_AUTO").performScrollTo().performClick()
        assertEquals(MapTheme.AUTO, settings.mapTheme)
    }
}
