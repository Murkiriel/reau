package com.reau.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "O REau e os seus dados" (the text that answers whether REau's privacy claim
 * is true, on a button of its own): what REau is, what leaves the phone and to where, what stays, what the rider
 * controls, and what is out of REau's reach.
 */
@RunWith(AndroidJUnit4::class)
class AboutDataScreenTest {
    @get:Rule val compose = createComposeRule()

    private var back = 0
    private val opened = mutableListOf<String>()

    @Before
    fun setUp() {
        compose.setContent { AboutDataScreen({ back++ }, { opened += "credits" }, { opened += "terms" }) }
    }

    /** The redesign: the blocks are folded but the first; "Abrir tudo" shows every text, never cut. */
    private fun section(tag: String) = compose.onNodeWithTag("about_${tag}_text", useUnmergedTree = true).performScrollTo()

    private fun openAll() = compose.onNodeWithTag("about_toggle_all").performScrollTo().performClick()

    @Test
    fun eachPartSaysWhatItShould() {
        openAll()
        section("what").assertTextContains("ponte local", substring = true)
        section("leaves").assertTextContains("painel", substring = true).assertTextContains("internet", substring = true)
        section("stays").assertTextContains("\"Compartilhar\"", substring = true)
        section("kept").assertTextContains("senha do Wi-Fi do painel", substring = true)
        section("control").assertTextContains("os registros e as gravações", substring = true)
            .assertTextContains("\"Permissões\" da tela inicial", substring = true)
        section("reach").assertTextContains("Gemini", substring = true)
        compose.onNodeWithTag("about_limits", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun backGoesBack() {
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun onlyTheFirstPartOpensByItselfAndAllOpenAndCloseTogether() {
        section("what").assertExists()
        compose.onNodeWithTag("about_leaves_text", useUnmergedTree = true).assertDoesNotExist()
        openAll()
        section("reach").assertExists()
        openAll()
        compose.onNodeWithTag("about_what_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theSummarySaysTheThreeThingsFirst() {
        compose.onNodeWithTag("about_summary").assertTextContains("Só usa a internet para o clima", substring = true)
            .assertTextContains("Só manda para o painel da sua moto.", substring = true)
            .assertTextContains("sem a sua posição", substring = true)
    }

    /** The terms of use and the credits sit at the end of the page; each tap hands over to its page. */
    @Test
    fun theTermsAndTheCreditsAreAtTheEndAndOpenTheirPages() {
        compose.onNodeWithTag("open_terms").performScrollTo().assertTextContains("Termos de uso").performClick()
        compose.onNodeWithTag("open_credits").performScrollTo().assertTextContains("Créditos").performClick()
        assertEquals(listOf("terms", "credits"), opened)
    }
}
