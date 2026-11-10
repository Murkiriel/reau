package com.reau.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the skeleton shows when it opens: its name and that nothing is running yet. */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theInstalledPackageIsTheREauOne() {
        assertEquals("com.reau.app", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test
    fun theScreenOpensWithTheAppNameAndTheStoppedStatus() {
        compose.onNodeWithTag("title").assertIsDisplayed().assertTextEquals("REau")
        compose.onNodeWithTag("status").assertIsDisplayed().assertTextEquals("Parado")
    }
}
