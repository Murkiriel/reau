package com.reau.app.ui

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen and the card mode: the button that sends the card, and what the screen says meanwhile. */
@RunWith(AndroidJUnit4::class)
class MainScreenCardTest {
    @get:Rule val compose = createComposeRule()

    private var toggles = 0
    private var cards = 0

    private fun show(state: MainUiState) {
        compose.setContent { MainScreen(state, { toggles++ }, {}, {}, MainExtras(onCard = { cards++ })) }
    }

    private fun text(tag: String, expected: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
    }

    private fun side(tag: String, label: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextContains(label).assertTextContains(value)
    }

    private fun cardRunning(aa: AaLinkState = AaLinkState.ACTIVE, dash: DashLink = DashLink.READY) =
        Projection(phase = Phase.RUNNING, androidAuto = aa, dash = dash, card = true)

    @Test
    fun stoppedItOffersToSendTheCard() {
        show(MainUiState())
        text("card", "Mandar o wallpaper")
        compose.onNodeWithTag("card").performClick()
        assertEquals(1 to 0, cards to toggles)
    }

    @Test
    fun theCardOnTheDashIsSaidSoWithWhichPicture() {
        show(MainUiState(cardRunning()))
        text("status", "Wallpaper no painel")
        side("side_picture", "IMAGEM", "wallpaper de legibilidade")
        side("side_dash", "PAINEL", "Pronto")
        compose.onNodeWithTag("side_android_auto").assertDoesNotExist()
        compose.onNodeWithTag("server_warning").assertDoesNotExist()
    }

    @Test
    fun ourOwnDefaultPictureIsCalledWallpaperDeTeste() {
        show(MainUiState(cardRunning(), cardName = "grade_medicao"))
        side("side_picture", "IMAGEM", "Wallpaper de teste")
    }

    @Test
    fun aRidersPictureIsNamed() {
        show(MainUiState(cardRunning(), cardName = "grade_300x180.png"))
        side("side_picture", "IMAGEM", "imagem escolhida (grade_300x180.png)")
    }

    @Test
    fun whileTheCardIsOnTheSwitchTurnsItOffAndTheCardButtonIsGone() {
        show(MainUiState(cardRunning(dash = DashLink.CONNECTING)))
        text("status", "Conectando")
        compose.onNodeWithTag("toggle").assertTextContains("Desligar")
        compose.onNodeWithTag("card").assertDoesNotExist()
        compose.onNodeWithTag("toggle").performClick()
        assertEquals(0 to 1, cards to toggles)
    }

    @Test
    fun whileAProjectionIsOnTheCardButtonIsGoneToo() {
        show(MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.CONNECTING, dash = DashLink.CONNECTING)))
        compose.onNodeWithTag("card").assertDoesNotExist()
        compose.onNodeWithTag("side_picture").assertDoesNotExist()
        side("side_android_auto", "ANDROID AUTO", "Conectando")
    }
}
