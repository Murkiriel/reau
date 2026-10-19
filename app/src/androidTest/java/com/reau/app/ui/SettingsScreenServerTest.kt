package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.service.AaServerStarter
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The switch "Ligar e desligar o servidor do Android Auto sozinho", off until switched on. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenServerTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)
    private var accessibility by mutableStateOf(AaServerStarter.Accessibility.OFF)
    private var opened = 0

    private fun show() {
        compose.setContent {
            SettingsScreen(
                settings, SettingsActions({ settings = it }, {}, {}, {}, onOpenAccessibility = { opened++ }),
                accessibility = accessibility,
            )
        }
    }

    @Test
    fun offUntilSwitchedOnAndSaysWhatItDoes() {
        show()
        compose.onNodeWithTag("aa_server_auto").performScrollTo().assertIsOff()
            .assertTextContains("você liga à mão", substring = true)
        compose.infoSays("aa_server_auto", "Iniciar servidor da unidade principal", "10 toques")
        compose.onNodeWithTag("aa_server_accessibility").assertDoesNotExist()
        compose.onNodeWithTag("open_accessibility").assertDoesNotExist()
    }

    @Test
    fun switchedOnWithTheServiceOffItLeadsToAccessibility() {
        show()
        compose.onNodeWithTag("aa_server_auto").performScrollTo().tapSwitch()
        assertEquals(true, settings.aaServerAuto)
        compose.onNodeWithTag("aa_server_auto").assertIsOn()
        compose.onNodeWithTag("aa_server_accessibility").performScrollTo().assertIsDisplayed()
            .assertTextContains("Permitir configurações restritas", substring = true)
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
    fun withTheServiceConnectedThereIsNothingToOpen() {
        settings = PlanSettings.PLAN.copy(aaServerAuto = true)
        accessibility = AaServerStarter.Accessibility.CONNECTED
        show()
        compose.onNodeWithTag("aa_server_accessibility").performScrollTo().assertTextContains("ativada", substring = true)
        compose.onNodeWithTag("open_accessibility").assertDoesNotExist()
    }

    @Test
    fun switchingItOffAgain() {
        settings = PlanSettings.PLAN.copy(aaServerAuto = true)
        show()
        compose.onNodeWithTag("aa_server_auto").performScrollTo().tapSwitch()
        assertEquals(false, settings.aaServerAuto)
        compose.onNodeWithTag("aa_server_accessibility").assertDoesNotExist()
    }
}
