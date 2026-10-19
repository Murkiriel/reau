package com.reau.app

import android.Manifest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ProjectionSides
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card button as the rider uses it: it switches the service on in the card mode (sides left empty here), and
 * the switch turns it off. The system's picture picker is not driven here: it is the system's own screen.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityCardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val originalCardSides = ProjectionService.cardSidesFactory

    @Before
    fun setUp() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        ProjectionService.cardSidesFactory = { _, _ -> ProjectionSides.None }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        SettingsStore.open(context).save(REauSettings())
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        compose.waitUntil(10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
        ProjectionService.cardSidesFactory = originalCardSides
        SettingsStore.open(context).save(REauSettings())
    }

    @Test
    fun theCardButtonSwitchesTheCardModeOnAndTheSwitchOff() {
        // No picture: the legibility card. Saved, then the screen reopened to read it (saved only in setUp,
        // after the screen had read the settings, the card on screen was whatever the test before left).
        SettingsStore.open(context).save(REauSettings(cardImage = null))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("card").performScrollTo().performClick()

        compose.waitUntil(10_000) { ProjectionService.projection.value.phase == Phase.RUNNING }
        assertTrue("the card mode", ProjectionService.projection.value.card)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("side_picture").assertTextContains("wallpaper de legibilidade") }.isSuccess }

        compose.onNodeWithTag("toggle").performScrollTo().performClick()
        compose.waitUntil(10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("card").assertTextEquals("Mandar o wallpaper") }.isSuccess }
    }

    @Test
    fun theMainScreenNamesThePickedPicture() {
        SettingsStore.open(context).save(REauSettings(cardImage = "file:///sdcard/grade.png"))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("card").performScrollTo().performClick()
        compose.waitUntil(10_000) { ProjectionService.projection.value.phase == Phase.RUNNING }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("side_picture").assertTextContains("imagem escolhida (grade.png)") }.isSuccess
        }
    }
}
