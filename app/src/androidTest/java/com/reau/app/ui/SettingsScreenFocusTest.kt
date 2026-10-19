package com.reau.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Taking Android Auto's blue focus away once had a switch; it is always on now and has no row. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenFocusTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) }
    }

    @Test
    fun thereIsNoSwitchForIt() {
        compose.onNodeWithTag("clear_aa_focus").assertDoesNotExist()
    }
}
