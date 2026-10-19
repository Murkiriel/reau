package com.reau.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.ServerResult
import com.reau.app.service.AaServerStarter
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The server card of the main screen with the automatic switch on. */
@RunWith(AndroidJUnit4::class)
class MainScreenServerTest {
    @get:Rule val compose = createComposeRule()

    private val serverOff = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF)
    private var retried = 0
    private var opened = 0
    private var aaSettings = 0

    private fun show(projection: Projection, server: ServerUi) {
        val extras = MainExtras(onRetryServer = { retried++ }, onOpenAccessibility = { opened++ })
        compose.setContent { MainScreen(MainUiState(projection, server = server), {}, { aaSettings++ }, {}, extras) }
    }

    @Test
    fun withTheSwitchOffTheCardIsAsBefore() {
        show(serverOff, ServerUi(auto = false, result = ServerResult.LOCKED))
        compose.onNodeWithTag("server_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open_aa_settings").assertIsDisplayed()
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("retry_aa_server").assertDoesNotExist()
        compose.onNodeWithTag("open_accessibility").assertDoesNotExist()
    }

    @Test
    fun aRefusedTrySaysWhyAndOffersToTryAgain() {
        show(serverOff, ServerUi(auto = true, result = ServerResult.LOCKED, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).performScrollTo().assertTextContains("Desbloqueie o celular", substring = true)
        compose.onNodeWithTag("retry_aa_server").performScrollTo().performClick()
        assertEquals(1, retried)
        compose.onNodeWithTag("open_aa_settings").assertIsDisplayed()
    }

    @Test
    fun developerModeOffIsSaidWithTheTenTaps() {
        show(serverOff, ServerUi(auto = true, result = ServerResult.DEVELOPER_MODE_OFF, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).performScrollTo().assertTextContains("10 vezes em \"Versão\"", substring = true)
    }

    @Test
    fun developerModeOffPutsAndroidAutosSettingsFirst() {
        // On a phone with developer mode off, the way to Android Auto's settings is the golden button, on the left.
        show(serverOff, ServerUi(auto = true, result = ServerResult.DEVELOPER_MODE_OFF, accessibility = AaServerStarter.Accessibility.CONNECTED))
        val settings = compose.onNodeWithTag("open_aa_settings").performScrollTo().getBoundsInRoot()
        val retry = compose.onNodeWithTag("retry_aa_server").getBoundsInRoot()
        assertTrue("Android Auto's settings first: $settings, then the retry: $retry", settings.left < retry.left)
        compose.onNodeWithTag("open_aa_settings").performClick()
        assertEquals(1, aaSettings)
        compose.onNodeWithTag("retry_aa_server").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun withoutTheServiceItLeadsToAccessibilityInsteadOfTryingAgain() {
        show(serverOff, ServerUi(auto = true, result = ServerResult.SERVICE_OFF, accessibility = AaServerStarter.Accessibility.OFF))
        compose.onNodeWithTag("aa_server_accessibility", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retry_aa_server").assertDoesNotExist()
        compose.onNodeWithTag("open_accessibility").performScrollTo().performClick()
        assertEquals("it asks first, naming the banks", 0, opened)
        compose.onNodeWithTag("accessibility_confirm").assertIsDisplayed()
        compose.onNodeWithTag("accessibility_cancel").performClick()
        assertEquals("Cancelar opens nothing", 0, opened)
        compose.onNodeWithTag("open_accessibility").performScrollTo().performClick()
        compose.onNodeWithTag("accessibility_confirm").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun whileWorkingThereIsNoSecondTry() {
        show(serverOff, ServerUi(auto = true, result = ServerResult.WORKING, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).performScrollTo().assertTextContains("Ligando o servidor", substring = true)
        compose.onNodeWithTag("retry_aa_server").assertDoesNotExist()
    }

    @Test
    fun whileSwitchingOffItSaysSoAndThereIsNoSecondTry() {
        // Switching off once said "Ligando".
        show(serverOff, ServerUi(auto = true, result = ServerResult.STOPPING, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).performScrollTo().assertTextContains("Desligando o servidor", substring = true)
        compose.onNodeWithTag("retry_aa_server").assertDoesNotExist()
    }

    @Test
    fun afterTheProjectionTheSwitchOffIsSaid() {
        show(Projection(), ServerUi(auto = true, result = ServerResult.LEFT_ON, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("server_warning").assertDoesNotExist()
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).performScrollTo().assertTextContains("ficou ligado", substring = true)
    }

    @Test
    fun aSwitchOffThatWorkedSaysNothingOnceTheProjectionIsOff() {
        // No "Servidor do Android Auto desligado." line under the wallpaper link.
        show(Projection(), ServerUi(auto = true, result = ServerResult.STOPPED, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aStartThatWorkedIsNotRepeatedOnceTheProjectionIsOff() {
        show(Projection(), ServerUi(auto = true, result = ServerResult.STARTED, accessibility = AaServerStarter.Accessibility.CONNECTED))
        compose.onNodeWithTag("aa_server_status", useUnmergedTree = true).assertDoesNotExist()
    }
}
