package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Android Auto na rede": the network server's state, the switch that turns it on only after Ligar, the rule that the
 * dash is not looked for, the browser section and the sound's choice. Only compiled here: it is proven on the phone.
 */
@RunWith(AndroidJUnit4::class)
class ServerScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)
    private var back = 0

    private fun show(networkOn: Boolean, projection: Projection = Projection()) {
        val state = MainUiState(projection, server = ServerUi(networkOn = networkOn))
        compose.setContent { ServerScreen(state, settings, { settings = it }) { back++ } }
    }

    private val running = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE)

    @Test
    fun theBarSaysItShortAndTheInfoSaysItWhole() {
        show(networkOn = false)
        compose.onNodeWithTag("server_screen_title").assertTextContains("Android Auto na rede")
        compose.onNodeWithTag("server_info").performClick()
        compose.onNodeWithTag("info_sheet").assertIsDisplayed()
        // The sheet's tag sits on its frame, which does not merge the texts inside: look for them under it.
        val inSheet = hasAnyAncestor(hasTestTag("info_sheet"))
        compose.onNode(hasText("Android Auto na rede") and inSheet).assertIsDisplayed()
        compose.onNode(hasText("não procura o painel", substring = true) and inSheet).assertExists()
    }

    @Test
    fun withTheNetworkServerOffAndTheProjectionOffItSaysToStartFirst() {
        show(networkOn = false)
        compose.onNodeWithTag("server_state").assertTextContains("Desligado")
        compose.onNodeWithTag("server_network_sub").assertTextContains("Ligar", substring = true)
        compose.onNodeWithTag("web_view_locked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("web_view").assertDoesNotExist()
    }

    @Test
    fun withTheProjectionRunningTheSwitchIsThereAndTurnsTheNetworkServerOn() {
        show(networkOn = false, projection = running)
        compose.onNodeWithTag("web_view").performScrollTo().assertIsOff().tapSwitch()
        assertEquals(true, settings.webView)
    }

    @Test
    fun withTheNetworkServerOnItSaysSoAndThatTheDashIsNotLookedFor() {
        settings = PlanSettings.PLAN.copy(webView = true)
        show(networkOn = true, projection = running)
        compose.onNodeWithTag("server_state").assertTextContains("Ligado na rede")
        compose.onNodeWithTag("server_rule").assertTextContains("não procura o painel", substring = true)
    }

    @Test
    fun theSoundChoiceAndTheBrowserSectionAreHere() {
        show(networkOn = false, projection = running)
        compose.onNodeWithTag("web_audio_phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("web_audio_both").performScrollTo().performClick()
        assertEquals(com.reau.app.settings.WebAudioMode.BOTH, settings.webAudio)
    }

    @Test
    fun theSwitchOfAndroidAutosOwnServerIsNotHereAnyMore() {
        show(networkOn = false)
        compose.onNodeWithTag("aa_server_auto").assertDoesNotExist()
    }

    @Test
    fun backLeavesTheScreen() {
        show(networkOn = false)
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
    }
}
