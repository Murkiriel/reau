package com.reau.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terms of use: as the gate in front of the app (two buttons, the Portuguese text first, the English one in a tab)
 * and as a page to read from the settings (no buttons).
 */
@RunWith(AndroidJUnit4::class)
class TermsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var accepted = 0
    private var declined = 0
    private var back = 0

    private fun show(gate: Boolean) = compose.setContent { TermsScreen(gate, { accepted++ }, { declined++ }, { back++ }) }

    /** The text read to its end: the button the foot shows until then takes the rider there. */
    private fun readToTheEnd() {
        compose.onNodeWithTag("terms_scroll_end").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("terms_accept").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theGateAsksToReadToTheEndBeforeTheTwoButtonsAppear() {
        show(gate = true)
        compose.onNodeWithTag("terms_scroll_end").assertIsDisplayed()
        compose.onNodeWithTag("terms_accept").assertDoesNotExist()
        compose.onNodeWithTag("terms_decline").assertDoesNotExist()
        compose.onNodeWithTag("terms_scroll_end").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("terms_accept").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("terms_scroll_end").assertDoesNotExist()
    }

    @Test
    fun scrollingToTheEndByHandAlsoShowsTheTwoButtons() {
        show(gate = true)
        compose.onNodeWithTag("terms_accept").assertDoesNotExist()
        compose.onNodeWithTag("terms_text").performTouchInput { swipeUp(startY = bottom, endY = top, durationMillis = 100) }
        repeat(40) { compose.onNodeWithTag("terms_text").performTouchInput { swipeUp(startY = bottom, endY = top, durationMillis = 60) } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("terms_accept").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("terms_decline").assertIsDisplayed()
    }

    @Test
    fun theGateShowsThePortugueseTextAndBothButtons() {
        show(gate = true)
        readToTheEnd()
        compose.onNodeWithTag("terms_lang_pt").assertIsSelected()
        compose.onAllNodesWithText("Termos de Uso").assertCountEquals(1)
        compose.onNodeWithTag("terms_accept").assertIsDisplayed()
        compose.onNodeWithTag("terms_decline").assertIsDisplayed()
        compose.onNodeWithTag("back").assertDoesNotExist()
    }

    @Test
    fun acceptingAndRefusingAreTwoDifferentCalls() {
        show(gate = true)
        readToTheEnd()
        compose.onNodeWithTag("terms_decline").performClick()
        assertEquals(0 to 1, accepted to declined)
        compose.onNodeWithTag("terms_accept").performClick()
        assertEquals(1 to 1, accepted to declined)
    }

    @Test
    fun theEnglishTextIsOneTapAway() {
        show(gate = true)
        compose.onNodeWithTag("terms_lang_en").performClick()
        compose.onNodeWithTag("terms_lang_en").assertIsSelected()
        compose.onAllNodesWithText("Terms of Use").assertCountEquals(1)
    }

    @Test
    fun readFromTheSettingsThereAreNoButtonsAndBackLeaves() {
        show(gate = false)
        compose.onNodeWithTag("terms_accept").assertDoesNotExist()
        compose.onNodeWithTag("terms_decline").assertDoesNotExist()
        // Already accepted: no language tabs, only the app's own language.
        compose.onNodeWithTag("terms_lang_pt").assertDoesNotExist()
        compose.onNodeWithTag("terms_lang_en").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
        assertEquals(0 to 0, accepted to declined)
    }
}

