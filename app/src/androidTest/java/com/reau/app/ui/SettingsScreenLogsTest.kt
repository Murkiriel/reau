package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rider's control over the logs: the ride log switched off from the screen, and
 * everything it wrote deleted (recordings too, by the second button), each deletion only after a confirmation.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenLogsTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(REauSettings())
    private val deletions = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        compose.setContent {
            SettingsScreen(
                settings,
                SettingsActions(
                    { settings = it }, {}, {}, {},
                    onDeleteLogs = { withRecordings, done -> deletions += withRecordings; done(3, 12L) },
                ),
            )
        }
        compose.openDebug()
    }

    @Test
    fun theLogsAreKeptThirtyDaysUntilTheRiderPicksAnother() {
        compose.onNodeWithTag("log_keep_30").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("log_keep_7").performScrollTo().assertTextContains("7 dias").performClick()
        assertEquals(7, settings.logKeepDays)
        compose.onNodeWithTag("log_keep_0").performScrollTo().assertTextContains("Nunca", substring = true).performClick()
        assertEquals(0, settings.logKeepDays)
    }

    @Test
    fun theLocationSwitchIsOffUntilSwitchedOn() {
        compose.onNodeWithTag("use_location").performScrollTo().assertIsOff()
        compose.onNodeWithTag("use_location").tapSwitch()
        assertEquals(true, settings.useLocation)
    }

    @Test
    fun theRideLogIsOnUntilSwitchedOffAndSaysWhatOffMeans() {
        compose.onNodeWithTag("ride_log").performScrollTo().assertIsOn()
            .assertTextContains("Gravando", substring = true)
        compose.infoSays("ride_log", "nada sai do celular sem você compartilhar")
        compose.onNodeWithTag("ride_log").tapSwitch()
        assertEquals(false, settings.rideLog)
        compose.onNodeWithTag("ride_log").assertIsOff().assertTextContains("nada é gravado", substring = true)
    }

    @Test
    fun deletingAsksFirstAndOnlyThenDeletes() {
        compose.onNodeWithTag("delete_logs").performScrollTo().performClick()
        assertEquals("nothing before the confirmation", emptyList<Boolean>(), deletions)
        compose.onNodeWithTag("delete_cancel").performClick()
        assertEquals("cancelled", emptyList<Boolean>(), deletions)
        compose.onNodeWithTag("delete_logs").performScrollTo().performClick()
        compose.onNodeWithTag("delete_confirm").performClick()
        assertEquals("the logs only", listOf(false), deletions)
        // The redesign: a Snackbar in place of the Toast says how much went.
        compose.onNodeWithText("Apagado. Arquivos: 3; tamanho em KB: 12").assertIsDisplayed()
    }

    @Test
    fun theSecondButtonDeletesTheRecordingsToo() {
        compose.onNodeWithTag("delete_logs_recordings").performScrollTo().performClick()
        compose.onNodeWithTag("delete_confirm").assertTextContains("Apagar", substring = true).performClick()
        assertEquals(listOf(true), deletions)
    }
}
