package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import com.reau.app.video.PhonePreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The switch that writes the stream's FPS and bitrate on the dash's
 * video is on the "Tela do Android Auto"; on, the options for its X, Y and size appear under it; off, none of that is there.
 */
@RunWith(AndroidJUnit4::class)
class StatsOverlayScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)

    @Before
    fun setUp() {
        compose.setContent {
            val s = settings
            AndroidAutoScreen(
                onBack = {},
                mask = AaScreenMask(s.dashMask, s.mapping, com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT) { settings = settings.copy(dashMask = it) },
                fit = AaScreenFit(s) { settings = it.sanitized() },
                stats = s.overlay,
            )
        }
    }

    @After
    fun tearDown() = PhonePreview.hide()

    @Test
    fun theSwitchIsOnTheAndroidAutoScreenAndOffAtFirst() {
        compose.onNodeWithTag("stats_overlay").performScrollTo().assertIsOff()
        compose.onNodeWithTag("stats_steps").assertDoesNotExist()
        compose.onNodeWithTag("aa_screen_stats").assertDoesNotExist()
        compose.onNodeWithTag("stats_overlay").tapSwitch()
        assertEquals(true, settings.statsOverlay)
        compose.infoSays("stats_overlay", "vermelho com borda preta", "X e Y")
    }

    /** The line under the switch's title was taken out; the info still says it. */
    @Test
    fun theSwitchHasNoLineUnderItsTitle() {
        compose.onNodeWithTag("stats_overlay").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Texto vermelho com borda preta no painel").assertDoesNotExist()
    }

    @Test
    fun onTheOptionsOfPlaceAndSizeAppearUnderIt() {
        settings = settings.copy(statsOverlay = true, statsX = 100, statsY = 50, statsSize = 20)
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_stats").assertIsDisplayed()
        compose.onNodeWithTag("stats_x_value").performScrollTo().assertTextEquals("100 px")
        compose.onNodeWithTag("stats_y_plus").performScrollTo().performClick()
        assertEquals(51, settings.statsY)
        compose.onNodeWithTag("stats_size_plus").performScrollTo().performClick()
        assertEquals(21, settings.statsSize)
        compose.onNodeWithTag("stats_x_minus").performScrollTo().performClick()
        assertEquals(99, settings.statsX)
    }

    /**
     * Where the node sits in the root, whether or not it is on the screen: boundsInRoot is clipped to the visible part, so
     * a step scrolled out of view reads as 0 (this was only right on a tall enough screen).
     */
    private fun position(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot

    /** The stats section was cramped; it is spaced and aligned as the fit's cards above it. */
    @Test
    fun theStatsStepsAreAlignedAndSpacedLikeTheFitOnes() {
        settings = settings.copy(statsOverlay = true)
        compose.waitForIdle()
        assertEquals("same inner edge", position("margin_w_minus10").x, position("stats_x_minus10").x, 1f)
        val fit = position("margin_h_minus10").y - position("margin_w_minus10").y
        assertEquals("X to Y as width to height", fit, position("stats_y_minus10").y - position("stats_x_minus10").y, 1f)
        assertEquals("Y to size likewise", fit, position("stats_size_minus10").y - position("stats_y_minus10").y, 1f)
    }
}
