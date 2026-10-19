package com.reau.app

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.dash.DashPairing
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.SettingsStore
import com.reau.app.settings.Terms
import com.reau.app.settings.TermsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terms of use in front of the app: with none accepted (or an older version) the first screen is the terms, with
 * no switch to start the projection behind it; accepting keeps the version and opens the main screen. The run's
 * other tests start with the terms accepted (KeepAppStateRunner).
 */
@RunWith(AndroidJUnit4::class)
class TermsGateTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        // The questions of Android that come after the terms (notifications, the location for the Wi-Fi list) are answered: no dialog over the screens.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .forEach { automation.grantRuntimePermission(context.packageName, it) }
        SettingsStore.open(context).save(PlanSettings.PLAN)
        DashPairing.open(context).forget()
        context.getSharedPreferences("reau_terms", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        TermsStore.open(context).accept()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** The two buttons only come at the end of the text: the one the foot shows until then takes the rider there. */
    private fun readToTheEnd() {
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("terms_scroll_end").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("terms_scroll_end").performClick()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("terms_accept").assertIsDisplayed() }.isSuccess }
    }

    @Test
    fun withNothingAcceptedTheTermsComeFirstAndThereIsNoSwitch() {
        launch()
        readToTheEnd()
        compose.onNodeWithTag("terms_accept").assertIsDisplayed()
        compose.onNodeWithTag("toggle").assertDoesNotExist()
    }

    @Test
    fun anOlderVersionAsksAgain() {
        TermsStore.open(context).accept(Terms.VERSION - 1)
        launch()
        readToTheEnd()
        compose.onNodeWithTag("terms_accept").assertIsDisplayed()
    }

    @Test
    fun acceptingKeepsTheVersionAndOpensTheMainScreen() {
        launch()
        readToTheEnd()
        compose.onNodeWithTag("terms_accept").performClick()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("toggle").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("terms_accept").assertDoesNotExist()
        assertEquals(Terms.VERSION, TermsStore.open(context).accepted())
    }

    @Test
    fun withTheCurrentVersionAcceptedTheAppOpensAtOnce() {
        TermsStore.open(context).accept()
        launch()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("toggle").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("terms_accept").assertDoesNotExist()
    }
}
