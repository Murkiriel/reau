package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashReadings
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bike's Wi-Fi on its own screen, with what lived in the settings' dash group:
 * the dash known and "Esquecer", the name's beginning, the password and joining by itself.
 */
@RunWith(AndroidJUnit4::class)
class WifiScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)
    private var pairing by mutableStateOf(PairingUi(ssid = "RE_TEST_1234", ssidPrefix = "RE_", password = "12345678"))
    private var forgot = 0
    private var back = 0
    private var settingsBack = 0
    private var askedNearby = 0
    private var scanAllowed by mutableStateOf(true)

    @Before
    fun setUp() {
        compose.setContent {
            WifiScreen(
                settings, pairing,
                SettingsActions(
                    { settings = it }, { pairing = it }, { forgot++; pairing = pairing.copy(ssid = "") }, { settingsBack++ },
                    onAskWifiList = { askedNearby++ },
                ),
                scanAllowed = scanAllowed,
            ) { back++ }
        }
    }

    // ── a clean install: the full name gone, and no way given to Android to find it ──────────────────────────────

    @Test
    fun withTheNameUnknownAndNoWayToFindItTheScreenSaysHowAndAsks() {
        pairing = pairing.copy(ssid = "")
        scanAllowed = false
        compose.onNodeWithTag("find_dash_help").performScrollTo().assertTextContains("localização", substring = true)
        compose.onNodeWithTag("allow_wifi_list").performScrollTo().performClick()
        assertEquals(1, askedNearby)
    }

    @Test
    fun withTheNameUnknownItCanBeTypedWhole() {
        pairing = pairing.copy(ssid = "")
        scanAllowed = false
        compose.onNodeWithTag("ssid_full").performScrollTo().performTextReplacement("RE_AB12_345678")
        assertEquals("RE_AB12_345678", pairing.ssid)
    }

    @Test
    fun withTheNameKnownThereIsNoHelpAndNoField() {
        scanAllowed = false
        compose.onNodeWithTag("ssid_known").performScrollTo()
        compose.onNodeWithTag("find_dash_help").assertDoesNotExist()
        compose.onNodeWithTag("ssid_full").assertDoesNotExist()
    }

    /** The app finds and fills in the Wi-Fi by itself: with the list allowed, it just searches. */
    @Test
    fun withTheListAllowedItSearchesByItself() {
        pairing = pairing.copy(ssid = "")
        compose.onNodeWithTag("dash_searching").performScrollTo().assertTextContains("Procurando", substring = true)
        compose.onNodeWithTag("allow_wifi_list").assertDoesNotExist()
        compose.onNodeWithTag("ssid_full").assertDoesNotExist()
    }

    @Test
    fun itIsTheBikesWifiAndGoesBackByItsOwnWay() {
        compose.onNodeWithTag("wifi_title").assertIsDisplayed().assertTextEquals("Dados da moto")
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
        assertEquals("the settings' own back is not this one", 0, settingsBack)
    }

    @Test
    fun theKnownDashCanBeForgotten() {
        // The name hidden until the rider asks to see it.
        compose.onNodeWithTag("ssid_known").performScrollTo().assertTextContains("Painel conhecido").assertTextContains("RE_••••34")
        assertTrue("hidden", runCatching { compose.onNodeWithTag("ssid_known").assertTextContains("RE_TEST_1234", substring = true) }.isFailure)
        compose.onNodeWithTag("show_ssid").performScrollTo().performClick()
        compose.onNodeWithTag("ssid_known").assertTextContains("RE_TEST_1234", substring = true)
        compose.onNodeWithTag("forget_dash").performScrollTo().performClick()
        assertEquals(1, forgot)
        compose.onNodeWithTag("ssid_known").performScrollTo()
            .assertTextContains("Nenhum painel conhecido", substring = true)
            .assertTextContains("Procurando", substring = true)
        compose.onNodeWithTag("forget_dash").assertDoesNotExist()
    }

    @Test
    fun joiningByItselfIsOffUntilSwitchedOnAndSaysWhatItDoes() {
        // The line under the title was taken out (the (i) says it all): the row is the title and the switch.
        compose.onNodeWithTag("auto_join_wifi").performScrollTo().assertIsOff()
            .assertTextContains("Entrar sozinho no Wi-Fi", substring = true)
        compose.onNodeWithText("Sem a janela", substring = true).assertDoesNotExist()
        compose.infoSays("auto_join_wifi", "sem a janela de escolha", "Android pergunta uma vez")
    }

    @Test
    fun joiningByItselfSwitchesOnAndOff() {
        compose.onNodeWithTag("auto_join_wifi").performScrollTo().tapSwitch()
        assertEquals(true, settings.autoJoinWifi)
        compose.onNodeWithTag("auto_join_wifi").assertIsOn()
        compose.onNodeWithTag("auto_join_wifi").tapSwitch()
        assertEquals(false, settings.autoJoinWifi)
    }

    // ── "Dados da moto": everything the dash says, read only ───────────────────────────────────────────────────────

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun theBikeDataStartsEmptyAndSaysSo() {
        compose.runOnIdle { DashReadings.clear() }
        compose.onNodeWithTag("bike_last_read").performScrollTo().assertTextContains("Nenhuma ainda", substring = true)
        compose.onNodeWithTag("bike_firmware").performScrollTo().assertTextContains("Ainda não disse", substring = true)
        // The range has its own group; with no reading it says so, has no time line, and shows no number.
        compose.onNodeWithTag("bike_range").performScrollTo().assertTextContains("Ainda não disse", substring = true)
        compose.onNodeWithTag("bike_range_at").assertDoesNotExist()
        // What the dash never says, or nobody knows what it is, is not on the screen at all.
        compose.onNodeWithTag("bike_vin").assertDoesNotExist()
        compose.onNodeWithTag("show_identity").assertDoesNotExist()
        compose.onNodeWithTag("show_raw").assertDoesNotExist()
    }

    @Test
    fun whatTheDashSaidShowsInWordsAndNothingUnnamedIsListed() {
        compose.runOnIdle {
            DashReadings.clear()
            DashReadings.onValue(0x0B, 0x02, byteArrayOf(0xAA.toByte(), 0) + ascii("00.02.07") + ascii("01.08.05"))
            DashReadings.onValue(0x03, 0x05, byteArrayOf(0x55))
            DashReadings.onValue(0x0C, 0x07, byteArrayOf(0x00, 0xBA.toByte()))
            DashReadings.flush()
        }
        compose.onNodeWithTag("bike_firmware").performScrollTo().assertTextContains("8.05", substring = true)
        compose.onNodeWithTag("bike_set_theme").performScrollTo().assertTextContains("Automático", substring = true)
        compose.onNodeWithTag("bike_raw_0C07").assertDoesNotExist()
        // The range (0C07 = 00BA = 186) is a named line now, in kilometres: no unit said yet, or the dash set to km.
        compose.onNodeWithTag("bike_range").performScrollTo().assertTextContains("186 km", substring = true)
        compose.onNodeWithTag("bike_range_at").performScrollTo().assertTextContains("Lida em", substring = true)
        compose.runOnIdle { DashReadings.clear() }
    }

    @Test
    fun theRangeFollowsTheDistanceUnitOfTheDashAndKeepsTheKilometres() {
        compose.runOnIdle {
            DashReadings.clear()
            DashReadings.onValue(0x03, 0x04, byteArrayOf(0xAA.toByte()))
            DashReadings.onValue(0x0C, 0x07, byteArrayOf(0x00, 0x4C))
            DashReadings.flush()
        }
        // 76 km are 47 mi.
        compose.onNodeWithTag("bike_range").performScrollTo().assertTextContains("47 mi (76 km)", substring = true)
        compose.runOnIdle {
            DashReadings.onValue(0x03, 0x04, byteArrayOf(0x55))
            DashReadings.flush()
        }
        compose.onNodeWithTag("bike_range").performScrollTo().assertTextContains("76 km", substring = true)
        compose.onNodeWithText("47 mi", substring = true).assertDoesNotExist()
        compose.runOnIdle { DashReadings.clear() }
    }
}
