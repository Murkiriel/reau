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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ajustes → Depuração shares the logs, with or without the recordings. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenShareTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(REauSettings())
    private val shared = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        compose.setContent {
            SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {}, onShareLogs = { shared += it }))
        }
        compose.openDebug()
    }

    @Test
    fun theLogsAloneAndTheLogsWithTheRecordings() {
        compose.onNodeWithTag("share_logs").performScrollTo().performClick()
        compose.onNodeWithTag("share_logs_recordings").performScrollTo().performClick()
        assertEquals(listOf(false, true), shared)
    }
}
