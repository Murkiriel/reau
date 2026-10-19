package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen, fed each state the projection can be in: what it says and what each control does. */
@RunWith(AndroidJUnit4::class)
class MainScreenStateTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(MainUiState())
    private var toggles = 0
    private var androidAutoSettings = 0
    private var settingsOpened = 0

    private fun show(projection: Projection, version: String? = "17.4.661804") {
        state = MainUiState(projection, version)
        compose.setContent {
            MainScreen(state, { toggles++ }, { androidAutoSettings++ }, { settingsOpened++ })
        }
    }

    private fun running(aa: AaLinkState = AaLinkState.CONNECTING, dash: DashLink = DashLink.CONNECTING) =
        Projection(phase = Phase.RUNNING, androidAuto = aa, dash = dash)

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    /** A side of the bridge (the redesign): its label in capitals and what it says now. */
    private fun side(tag: String, label: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextContains(label).assertTextContains(value)
    }

    /** The main button: Ligar, or Desligar with the time since the start beside it. */
    private fun button(expected: String) {
        compose.onNodeWithTag("toggle").performScrollTo().assertIsDisplayed().assertTextContains(expected)
    }

    // ── what it says ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun stoppedItSaysSoAndOffersToSwitchOn() {
        show(Projection())
        // The fixed top bar (the redesign): it never scrolls.
        compose.onNodeWithTag("title").assertIsDisplayed().assertTextEquals("REau")
        text("status", "Parado")
        text("status_sub", "Toque em Ligar antes de sair")
        side("side_android_auto", "ANDROID AUTO", "Desligado")
        side("side_dash", "PAINEL", "Desligado")
        button("Ligar")
        compose.onNodeWithTag("server_warning").assertDoesNotExist()
    }

    @Test
    fun aStartThatFailedSaysWhy() {
        show(Projection(problem = "SecurityException: no type"))
        text("status", "Não ligou")
        text("status_sub", "SecurityException: no type")
        button("Ligar")
    }

    @Test
    fun preparing() {
        show(Projection(phase = Phase.PREPARING))
        text("status", "Preparando")
        side("side_android_auto", "ANDROID AUTO", "Conectando")
        side("side_dash", "PAINEL", "Conectando")
        button("Desligar")
    }

    @Test
    fun connectingWithBothSides() {
        show(running())
        text("status", "Conectando")
        side("side_android_auto", "ANDROID AUTO", "Conectando")
        side("side_dash", "PAINEL", "Conectando")
        button("Desligar")
    }

    @Test
    fun streaming() {
        show(running(AaLinkState.ACTIVE, DashLink.READY))
        text("status", "Transmitindo")
        side("side_android_auto", "ANDROID AUTO", "Ativo")
        side("side_dash", "PAINEL", "Pronto")
    }

    @Test
    fun aLostDash() {
        // It was ready once in this session: only then is it lost.
        show(running(AaLinkState.ACTIVE, DashLink.DROPPED).copy(dashWasReady = true))
        text("status", "Painel perdido, tentando de novo")
        side("side_dash", "PAINEL", "Caiu, tentando de novo")
    }

    @Test
    fun aDashNeverReachedIsBeingLookedFor() {
        show(running(AaLinkState.ACTIVE, DashLink.DROPPED))
        text("status", "Procurando o painel")
        side("side_dash", "PAINEL", "Procurando")
    }

    @Test
    fun aDashNeverJoinedFromHereHintsAtAndroidsQuestion() {
        // Escrito, ainda não rodado em aparelho: a primeira entrada no Wi-Fi do painel faz o Android perguntar.
        show(running(AaLinkState.ACTIVE, DashLink.CONNECTING).copy(dashFirstJoin = true))
        text("status", "Procurando o painel")
        text("status_sub", "O Android pode perguntar se o REau pode conectar ao painel: toque em Conectar")
    }

    @Test
    fun aDashOutOfReachDoesNotPromiseAndroidsQuestion() {
        // Escrito, não rodado em aparelho: com a moto desligada o Android não pergunta nada (sessão real de 18/10);
        // sem a dica, a linha de apoio é a de sempre.
        show(running(AaLinkState.ACTIVE, DashLink.DROPPED).copy(dashFirstJoin = false))
        text("status", "Procurando o painel")
        text("status_sub", "O REau segue tentando entrar no Wi-Fi do painel")
    }

    @Test
    fun aDroppedAndroidAuto() {
        show(running(AaLinkState.DROPPED, DashLink.READY))
        side("side_android_auto", "ANDROID AUTO", "Caiu, ligando de novo")
    }

    @Test
    fun theAndroidAutoServerOffIsExplainedWithAWayToItsSettings() {
        show(running(AaLinkState.SERVER_OFF))
        text("status", "Android Auto sem servidor")
        side("side_android_auto", "ANDROID AUTO", "Servidor desligado")
        compose.onNodeWithTag("server_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open_aa_settings").performScrollTo().performClick()
        assertEquals(1, androidAutoSettings)
    }

    @Test
    fun theInstalledAndroidAutoIsShown() {
        show(Projection(), version = "17.4.661804")
        text("aa_version", "Android Auto instalado · 17.4.661804")
    }

    @Test
    fun noAndroidAutoIsSaid() {
        show(Projection(), version = null)
        text("aa_version", "Android Auto não instalado")
    }

    // ── what the controls do ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun theAndroidAutoSettingsShortcutIsAlwaysThere() {
        // A way into Android Auto's settings at any time, not only with its server off.
        show(Projection())
        compose.onNodeWithTag("aa_settings_shortcut").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, androidAutoSettings)
        state = MainUiState(running(AaLinkState.ACTIVE, DashLink.READY), "17.4.661804")
        compose.onNodeWithTag("aa_settings_shortcut").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(2, androidAutoSettings)
    }

    @Test
    fun theTwoAndroidAutoShortcutsSitTogetherAndThereIsNoWazeCard() {
        // The settings shortcut went far down, under the Waze card, which is not useful: the card
        // goes, and the settings shortcut sits right under "Ver a tela do Android Auto" (in the redesign's grid, the
        // tile under the Android Auto screen's).
        show(running(AaLinkState.ACTIVE, DashLink.READY))
        compose.onNodeWithTag("nav_go").assertDoesNotExist()
        val view = compose.onNodeWithTag("show_aa_screen").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag("aa_settings_shortcut").fetchSemanticsNode().boundsInRoot
        val gapDp = (settings.top - view.bottom) / compose.density.density
        assertTrue("the settings shortcut is ${gapDp}dp under the screen's button", gapDp in 0f..40f)
    }

    @Test
    fun theSwitchAndTheSettingsButtonCallBack() {
        show(Projection())
        compose.onNodeWithTag("toggle").performScrollTo().performClick()
        compose.onNodeWithTag("open_settings").performScrollTo().performClick()
        assertEquals(1, toggles)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun theScreenFollowsTheStateAsItChanges() {
        show(Projection())
        button("Ligar")
        state = MainUiState(running(AaLinkState.ACTIVE, DashLink.READY), "17.4")
        text("status", "Transmitindo")
        button("Desligar")
    }
}
