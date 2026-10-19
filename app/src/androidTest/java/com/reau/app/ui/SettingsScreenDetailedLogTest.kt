package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Registro detalhado" switch: off until the rider switches it on. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenDetailedLogTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(REauSettings())

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {})) }
        compose.openDebug()
    }

    @Test
    fun offUntilSwitchedOnAndSaysWhatItDoes() {
        compose.onNodeWithTag("detailed_log").performScrollTo().assertIsOff()
            .assertTextContains("Registro detalhado", substring = true)
    }

    @Test
    fun switchingItOnAndOff() {
        compose.onNodeWithTag("detailed_log").performScrollTo().tapSwitch()
        assertEquals(true, settings.detailedLog)
        compose.onNodeWithTag("detailed_log").assertIsOn()
        compose.onNodeWithTag("detailed_log").tapSwitch()
        assertEquals(false, settings.detailedLog)
    }
}
