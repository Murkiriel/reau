package com.reau.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Android Auto 1.7 protocol had a test switch; it is always asked for now and has no row. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenProtocolTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) }
    }

    @Test
    fun thereIsNoSwitchForIt() {
        compose.openDebug()
        compose.onNodeWithTag("aa_new_protocol").assertDoesNotExist()
    }
}
