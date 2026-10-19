package com.reau.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The network server is switched on after Ligar, never before (the switch is off at each start),
 * so with the projection off the switch is a locked row that says to start first. Only compiled here.
 */
@RunWith(AndroidJUnit4::class)
class WebSectionLockedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun withTheProjectionOffTheSwitchCannotBeUsedAndSaysToStartFirst() {
        // In a column that scrolls, as on the network server's screen (performScrollTo needs one).
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { WebSection(REauSettings(), projectionOn = false) {} } }
        compose.onNodeWithTag("web_view_locked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("web_view_locked").assertTextContains("Ligar", substring = true)
        compose.onNodeWithTag("web_view").assertDoesNotExist()
    }
}
