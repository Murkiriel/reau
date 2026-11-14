package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaScreen
import com.reau.app.settings.REauSettings
import com.reau.app.video.PhonePreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-time fit assistant on the "Tela do Android Auto" (13/11/2026): open by itself when the screen is told the phone never saved a
 * fit, behind its quiet button otherwise; four steps, the navigation tab's own counters behind a "no", and every button 48 dp tall.
 */
@RunWith(AndroidJUnit4::class)
class FitAssistantScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(REauSettings())
    private var seen = 0

    private fun show(pending: Boolean, screen: AaScreen = AaScreen.NAV) {
        compose.setContent {
            val s = settings
            AndroidAutoScreen(
                onBack = {},
                mask = AaScreenMask(s.dashMask, s.mappingFor(screen), com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT) { settings = settings.copy(dashMask = it) },
                fit = AaScreenFit(s, screen, FitAssistantAsk(pending) { seen++ }) { settings = it.sanitized() },
            )
        }
    }

    @After
    fun tearDown() = PhonePreview.hide()

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun aPhoneWithAFitSavedSeesOnlyTheQuietButton() {
        show(pending = false)
        compose.onNodeWithTag("fit_assistant").assertDoesNotExist()
        compose.onNodeWithTag("fit_assistant_open").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(REauSettings(), settings)
    }

    @Test
    fun aPhoneThatNeverSavedAFitSeesItOpenAndCanSkipIt() {
        show(pending = true)
        compose.onNodeWithTag("fit_assistant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_assistant_step").assertTextEquals("Passo 1 de 4")
        compose.onNodeWithTag("fit_assistant_skip").performScrollTo().assertHeightIsAtLeast(48.dp)
        tap("fit_assistant_skip")
        compose.onNodeWithTag("fit_assistant").assertDoesNotExist()
        compose.onNodeWithTag("fit_assistant_open").performScrollTo().assertIsDisplayed()
        assertEquals(1, seen)
        assertEquals("skipping changes nothing", REauSettings(), settings)
    }

    @Test
    fun yesTwiceGoesToTheEndAndChangesNothingOfTheFit() {
        settings = REauSettings(dashMask = false)
        show(pending = true)
        tap("fit_assistant_begin")
        assertTrue("starting turns the mask on", settings.dashMask)
        compose.onNodeWithTag("fit_assistant_step").assertTextEquals("Passo 2 de 4")
        compose.onNodeWithTag("fit_assistant_crop_center_x_value").assertDoesNotExist()
        for (tag in listOf("fit_assistant_yes", "fit_assistant_no", "fit_assistant_back")) {
            compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
        tap("fit_assistant_yes")
        tap("fit_assistant_yes")
        compose.onNodeWithTag("fit_assistant_step").assertTextEquals("Passo 4 de 4")
        compose.onNodeWithTag("fit_assistant_finish").performScrollTo().assertHeightIsAtLeast(48.dp)
        tap("fit_assistant_finish")
        assertEquals(1, seen)
        assertEquals(REauSettings(), settings)
    }

    @Test
    fun aNoOpensTheNavigationFitsOwnCountersForThatStep() {
        show(pending = true)
        tap("fit_assistant_begin")
        tap("fit_assistant_no")
        compose.onNodeWithTag("fit_assistant_crop_center_x_value").performScrollTo().assertTextEquals("90 px")
        compose.onNodeWithTag("fit_assistant_crop_value").assertDoesNotExist()
        tap("fit_assistant_crop_center_x_minus10")
        tap("fit_assistant_crop_center_y_plus")
        assertEquals(80 to 63, settings.navFit.cropX to settings.navFit.cropY)
        assertEquals("the other tab's fit is not the assistant's", REauSettings().appsFit, settings.appsFit)
        compose.onNodeWithTag("fit_assistant_good").performScrollTo().assertHeightIsAtLeast(48.dp)
        tap("fit_assistant_good")
        compose.onNodeWithTag("fit_assistant_step").assertTextEquals("Passo 3 de 4")
        tap("fit_assistant_no")
        compose.onNodeWithTag("fit_assistant_crop_value").performScrollTo().assertTextEquals("74%")
        compose.onNodeWithTag("fit_assistant_crop_center_x_value").assertDoesNotExist()
        tap("fit_assistant_back")
        compose.onNodeWithTag("fit_assistant_crop_value").assertDoesNotExist()
        compose.onNodeWithTag("fit_assistant_step").assertTextEquals("Passo 3 de 4")
        assertEquals(0, seen)
    }

    @Test
    fun itSaysWhenAndroidAutoIsNotOnANavigationAppAndWhenTheDensityWasNotMeasured() {
        settings = REauSettings(density = 200)
        show(pending = true, screen = AaScreen.MENU)
        compose.onNodeWithTag("fit_assistant_no_nav").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_assistant_density").performScrollTo().assertIsDisplayed()
        assertFalse(FitAssistant.densityMeasured(settings))
    }

    @Test
    fun theQuietButtonOpensItByHand() {
        show(pending = false)
        tap("fit_assistant_open")
        compose.onNodeWithTag("fit_assistant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fit_assistant_open").assertDoesNotExist()
        assertEquals(0, seen)
    }
}
