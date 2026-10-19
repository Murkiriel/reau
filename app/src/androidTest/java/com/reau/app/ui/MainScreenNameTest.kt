package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen when the dash's full Wi-Fi name is not known: what it says, and what to do. */
@RunWith(AndroidJUnit4::class)
class MainScreenNameTest {
    @get:Rule val compose = createComposeRule()

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    @Test
    fun aDashWithoutItsFullNameIsSaidSoWithWhatToDo() {
        compose.setContent {
            MainScreen(MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.NAME_NEEDED)), {}, {}, {})
        }
        text("status", "Falta o nome inteiro do Wi-Fi do painel")
        compose.onNodeWithTag("side_dash").performScrollTo().assertTextContains("PAINEL").assertTextContains("Falta o nome do Wi-Fi")
        compose.onNodeWithTag("dash_name_warning").performScrollTo().assertIsDisplayed()
            .assertTextContains("Esquecer painel", substring = true)
            // A made-up example, not a real dash's name.
            .assertTextContains("RE_AB12_345678", substring = true)
    }

    @Test
    fun anyOtherDashStateHasNoSuchWarning() {
        var projection by mutableStateOf(Projection())
        compose.setContent { MainScreen(MainUiState(projection), {}, {}, {}) }
        for (link in DashLink.entries - DashLink.NAME_NEEDED) {
            projection = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = link)
            compose.onNodeWithTag("side_dash").assertIsDisplayed()
            compose.onNodeWithTag("dash_name_warning").assertDoesNotExist()
        }
    }
}
