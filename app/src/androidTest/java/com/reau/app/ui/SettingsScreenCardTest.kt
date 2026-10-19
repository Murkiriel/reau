package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The card section of the settings: which picture the card button sends, picking one, going back. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenCardTest {
    @get:Rule val compose = createComposeRule()

    // The first defaults (no picture picked); the app's default card is now the built-in measuring grid.
    private var settings by mutableStateOf(PlanSettings.PLAN)
    private var picks = 0

    @Before
    fun setUp() {
        compose.setContent {
            SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {}, onPickCardImage = { picks++ }))
        }
    }

    @Test
    fun theGroupIsCalledWallpaperAndTheRowHasNoTextUnderIt() {
        compose.onNodeWithTag("group_card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("card_now").performScrollTo().assertTextContains("Imagem para o painel")
        compose.onAllNodesWithText("imagem escolhida", substring = true).assertCountEquals(0)
    }

    @Test
    fun pickingAPictureAsksForOne() {
        compose.onNodeWithTag("card_pick").performScrollTo().performClick()
        assertEquals(1, picks)
    }

    @Test
    fun backToTheDefaultIsAlwaysThereAndBringsTheTestPictureBack() {
        settings = settings.copy(cardImage = REauSettings.DEFAULT_CARD)
        compose.onNodeWithTag("card_legibility").performScrollTo().assertTextContains("Voltar ao padrão")
        settings = settings.copy(cardImage = "content://com.android.externalstorage.documents/document/primary%3APictures%2Fgrade.png")
        compose.onNodeWithTag("card_legibility").performScrollTo().performClick()
        assertEquals("our own picture, until another is picked", REauSettings.DEFAULT_CARD, settings.cardImage)
        compose.onNodeWithTag("card_legibility").assertTextContains("Voltar ao padrão")
    }
}
