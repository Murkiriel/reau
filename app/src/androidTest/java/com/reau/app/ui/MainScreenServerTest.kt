package com.reau.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The server card of the main screen: how to turn Android Auto's head unit server on by hand, and the way to Android Auto's settings. */
@RunWith(AndroidJUnit4::class)
class MainScreenServerTest {
    @get:Rule val compose = createComposeRule()

    private val serverOff = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF)
    private var aaSettings = 0

    private fun show(projection: Projection) {
        compose.setContent { MainScreen(MainUiState(projection), {}, { aaSettings++ }, {}, MainExtras()) }
    }

    @Test
    fun theCardTellsHowToTurnTheServerOnByHandAndOffersAndroidAutosSettings() {
        show(serverOff)
        compose.onNodeWithTag("server_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open_aa_settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open_aa_settings").performClick()
        assertEquals(1, aaSettings)
    }

    @Test
    fun theCardHasNoStatusLineAndNoRetryButton() {
        show(serverOff)
        compose.onNodeWithTag("server_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("retry_aa_server").assertDoesNotExist()
    }

    @Test
    fun withTheProjectionOffThereIsNoServerCardAndNoLineUnderTheWallpaperLink() {
        show(Projection())
        compose.onNodeWithTag("server_warning").assertDoesNotExist()
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).assertDoesNotExist()
    }
}
