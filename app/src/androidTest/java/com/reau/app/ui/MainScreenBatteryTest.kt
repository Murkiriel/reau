package com.reau.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Battery saver matters: the main screen asks to leave
 * REau out of battery optimization and says when battery saver is on, each with its way to Android's setting.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenBatteryTest {
    @get:Rule val compose = createComposeRule()

    private var asked = 0
    private var opened = 0

    private fun show(battery: BatteryUi) {
        val extras = MainExtras(onAskBatteryExemption = { asked++ }, onOpenBatterySaver = { opened++ })
        compose.setContent { MainScreen(MainUiState(Projection(phase = Phase.RUNNING), battery = battery), {}, {}, {}, extras) }
    }

    @Test
    fun exemptAndWithBatterySaverOffNothingIsShown() {
        show(BatteryUi(saverOn = false, exempt = true))
        compose.onNodeWithTag("battery_exemption").assertDoesNotExist()
        compose.onNodeWithTag("battery_saver").assertDoesNotExist()
    }

    @Test
    fun notExemptTheCardAsksAndTheButtonOpensAndroidsDialog() {
        show(BatteryUi(saverOn = false, exempt = false))
        compose.onNodeWithTag("battery_exemption").performScrollTo().assertIsDisplayed()
            .assertTextContains("otimização de bateria", substring = true)
        compose.onNodeWithTag("ask_battery_exemption").performScrollTo().performClick()
        assertEquals(1, asked)
        compose.onNodeWithTag("battery_saver").assertDoesNotExist()
    }

    @Test
    fun batterySaverOnIsSaidWithTheWayToItsSettings() {
        show(BatteryUi(saverOn = true, exempt = true))
        compose.onNodeWithTag("battery_saver").performScrollTo().assertIsDisplayed()
            .assertTextContains("Economia de bateria ligada", substring = true)
        compose.onNodeWithTag("open_battery_saver").performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("battery_exemption").assertDoesNotExist()
    }

    /** Observed on a device: a VPN held REau and the bike's Wi-Fi was refused; the card says what to do. */
    @Test
    fun aVpnHoldingREauIsSaidWithWhatToDo() {
        compose.setContent { MainScreen(MainUiState(Projection(), vpnHoldsREau = true), {}, {}, {}) }
        compose.onNodeWithTag("vpn_warning").performScrollTo().assertIsDisplayed()
            .assertTextContains("VPN", substring = true)
            .assertTextContains("dividir túnel", substring = true)
    }
}
