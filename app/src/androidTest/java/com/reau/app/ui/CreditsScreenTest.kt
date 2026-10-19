package com.reau.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.settings.Credits
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The credits page: each project on its line, name, "© ·
 * licence" and role, in the three groups; a tap shows the address and
 * copies it (REau opens no browser itself).
 */
@RunWith(AndroidJUnit4::class)
class CreditsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var back = 0
    private val copied = mutableListOf<String>()

    @Before
    fun setUp() {
        compose.setContent { CreditsScreen(onBack = { back++ }, onCopy = { copied += it }) }
    }

    private fun line(name: String) = compose.onNodeWithTag("credit_$name").performScrollTo()

    @Test
    fun everyProjectHasItsLineWithHolderLicenceAndRole() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (e in Credits.groups.flatMap { it.entries }) {
            val shownName = if (e.nameText != 0) context.getString(e.nameText) else e.name
            line(e.name).assertIsDisplayed().assertTextContains(shownName, substring = true)
                .assertTextContains(e.licence, substring = true).assertTextContains(context.getString(e.role), substring = true)
        }
        line("NorthStar").assertTextContains("© 2026 Aditya Dasika", substring = true)
    }

    @Test
    fun theGroupsAndTheIndependenceLineAreShown() {
        for (side in Credits.Side.entries) compose.onNodeWithTag("credits_group_$side").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("credits_independent").performScrollTo().assertTextContains("Royal Enfield", substring = true)
    }

    @Test
    fun aTapShowsTheAddressAndCopiesIt() {
        line("recon-dash").performClick()
        line("recon-dash").assertTextContains("https://github.com/MinotaurG/recon-dash", substring = true)
        assertEquals(listOf("https://github.com/MinotaurG/recon-dash"), copied)
        // The redesign: a Snackbar says so.
        compose.onNodeWithText("Endereço copiado").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun theSourceCodeLineSitsOnTopAndACopyGivesTheAddress() {
        compose.onNodeWithTag("credits_group_source").performScrollTo().assertIsDisplayed()
        line("source").performClick()
        line("source").assertTextContains(Credits.SOURCE_URL, substring = true).assertTextContains("AGPL-3.0", substring = true)
        assertEquals(listOf(Credits.SOURCE_URL), copied)
    }

    @Test
    fun theFontsAndTheIconsAreCreditedAndTheNameIsOnTop() {
        compose.onNodeWithTag("credits_brand").assertIsDisplayed()
        // The app's icon sits beside the name.
        compose.onNodeWithTag("credits_brand_icon").assertIsDisplayed()
        for (name in listOf("Fraunces", "Hanken Grotesk", "JetBrains Mono")) line(name).assertTextContains("OFL-1.1", substring = true)
        line("Material Symbols").assertTextContains("Apache-2.0", substring = true)
    }
}
