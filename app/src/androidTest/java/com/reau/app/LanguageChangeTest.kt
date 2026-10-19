package com.reau.app

import android.os.Build
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.dash.DashPairing
import com.reau.app.settings.AppLanguage
import com.reau.app.settings.AppLanguages
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.SettingsStore
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The language is changed on the settings: the texts change at once and the screen is the same one, not destroyed and
 * rebuilt (it showed as a flash). From Android 13, where the system holds the language and recreates the screen unless
 * the activity says it handles the change (`configChanges="locale"` in the manifest).
 */
@RunWith(AndroidJUnit4::class)
class LanguageChangeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        assumeTrue("the system holds the language from Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        SettingsStore.open(context).save(PlanSettings.PLAN)
        DashPairing.open(context).forget()
    }

    private fun waitForStatus(text: String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("status").assertTextEquals(text) }.isSuccess }
    }

    @Test
    fun theTextsChangeAtOnceAndTheScreenIsNotRebuilt() {
        val before = compose.activity
        compose.onNodeWithTag("status").assertTextEquals("Parado") // the run speaks Portuguese (KeepAppStateRunner)
        try {
            compose.runOnUiThread { AppLanguages.set(before, AppLanguage.EN) }
            waitForStatus("Stopped")
            assertSame("the screen was rebuilt", before, compose.activity)
        } finally {
            compose.runOnUiThread { AppLanguages.set(before, AppLanguage.PT) }
            waitForStatus("Parado")
        }
        assertSame("the screen was rebuilt on the way back", before, compose.activity)
    }
}
