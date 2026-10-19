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

/** The main screen with the dash held by another app: what it says, and the warning. */
@RunWith(AndroidJUnit4::class)
class MainScreenTakenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(projection: Projection) {
        compose.setContent { MainScreen(MainUiState(projection), {}, {}, {}) }
    }

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    @Test
    fun aTakenDashIsSaidSoWithWhatToDo() {
        show(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.TAKEN, dashRetries = 1))
        text("status", "Outro app está com o painel")
        compose.onNodeWithTag("side_dash").performScrollTo().assertTextContains("PAINEL").assertTextContains("Ocupado por outro app")
        compose.onNodeWithTag("dash_taken_warning").performScrollTo().assertIsDisplayed()
            .assertTextContains("outro app de painel", substring = true)
            .assertTextContains("tenta de novo sozinho", substring = true)
    }

    @Test
    fun anyOtherDashStateHasNoSuchWarning() {
        var projection by mutableStateOf(Projection())
        compose.setContent { MainScreen(MainUiState(projection), {}, {}, {}) }
        for (link in listOf(DashLink.OFF, DashLink.CONNECTING, DashLink.READY, DashLink.DROPPED)) {
            projection = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = link)
            compose.onNodeWithTag("side_dash").assertIsDisplayed()
            compose.onNodeWithTag("dash_taken_warning").assertDoesNotExist()
        }
    }

    @Test
    fun inTheCardModeTooTheWarningShows() {
        show(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.TAKEN, card = true))
        text("status", "Outro app está com o painel")
        compose.onNodeWithTag("dash_taken_warning").performScrollTo().assertIsDisplayed()
    }
}
