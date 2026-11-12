package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import com.reau.app.video.PhonePreview
import com.reau.app.weather.RingData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The switch of the dash's info ring is on the "Tela do Android Auto" (off by default; this test starts it off), over the one of the FPS and bitrate text;
 * on, with "Usar a localização" off, a line says what it needs.
 */
@RunWith(AndroidJUnit4::class)
class InfoRingScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN.copy(useLocation = false))

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
    fun theSwitchIsOffAtFirstAndTurnsOnAndOff() {
        compose.onNodeWithTag("info_ring").performScrollTo().assertIsOff()
        compose.onNodeWithTag("info_ring").tapSwitch()
        assertEquals(true, settings.infoRing)
        compose.onNodeWithTag("info_ring").assertIsOn()
        compose.onNodeWithTag("info_ring").tapSwitch()
        assertEquals(false, settings.infoRing)
    }

    @Test
    fun theSwitchIsOverTheOneOfTheStatsText() {
        val ring = compose.onNodeWithTag("info_ring").performScrollTo().fetchSemanticsNode().boundsInRoot
        val stats = compose.onNodeWithTag("stats_overlay").performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue("the info ring at $ring, the stats text at $stats", ring.top < stats.top)
    }

    @Test
    fun theInfoSaysWhatItIsAndWhereItIs() {
        compose.infoSays("info_ring", "à direita", "botão para baixo")
    }

    @Test
    fun withTheLocationOffAndTheRingOnALineSaysWhatItNeeds() {
        compose.onNodeWithTag("info_ring_needs_location").assertDoesNotExist()
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_needs_location").performScrollTo().assertIsDisplayed()
        settings = settings.copy(useLocation = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_needs_location").assertDoesNotExist()
    }

    // What the ring says and the carousel.

    @Test
    fun theChoicesAreThereOnlyWithTheRingOn() {
        compose.onNodeWithTag("info_ring_choices").assertDoesNotExist()
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_choices").performScrollTo().assertIsDisplayed()
        // At first only the rain, the wind and the thunder are ticked (12/11/2026); the other cards are there, off, one tick away.
        for (data in RingData.entries) {
            val box = compose.onNodeWithTag("info_ring_data_${data.name.lowercase()}").performScrollTo()
            if (RingData.shown(RingData.DEFAULT, data)) box.assertIsOn() else box.assertIsOff()
        }
        compose.onNodeWithTag("info_ring_data_none").assertDoesNotExist()
    }

    @Test
    fun theRingStartsWithRainWindAndThunderOnlyAndAnotherCardIsOneTickAway() {
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_data_rain").performScrollTo().assertIsOn()
        compose.onNodeWithTag("info_ring_data_wind").performScrollTo().assertIsOn()
        compose.onNodeWithTag("info_ring_data_storm").performScrollTo().assertIsOn()
        for (tag in listOf("uv", "air", "sun", "sky", "temps", "battery", "link", "gps")) {
            compose.onNodeWithTag("info_ring_data_$tag").performScrollTo().assertIsOff()
        }
        compose.onNodeWithTag("info_ring_data_uv").performClick()
        assertEquals(RingData.DEFAULT or RingData.UV.bit, settings.infoRingData)
        compose.onNodeWithTag("info_ring_data_uv").assertIsOn()
    }

    @Test
    fun aCheckboxTakesItsKindOfDataInAndOut() {
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_data_rain").performScrollTo().performClick()
        assertEquals(RingData.DEFAULT and RingData.RAIN.bit.inv(), settings.infoRingData)
        compose.onNodeWithTag("info_ring_data_rain").assertIsOff()
        compose.onNodeWithTag("info_ring_data_wind").assertIsOn()
        compose.onNodeWithTag("info_ring_data_rain").performClick()
        assertEquals(RingData.DEFAULT, settings.infoRingData)
    }

    @Test
    fun theSwitchOfThePlaceIsThereOnlyWithTheRingOnAndKeepsItsChoice() {
        compose.onNodeWithTag("info_ring_in_place").assertDoesNotExist()
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_in_place").performScrollTo().assertIsOff()
        compose.onNodeWithTag("info_ring_in_place").performScrollTo().tapSwitch()
        assertEquals(true, settings.infoRingInPlace)
        compose.onNodeWithTag("info_ring_in_place").assertIsOn()
        compose.onNodeWithTag("info_ring_in_place").tapSwitch()
        assertEquals(false, settings.infoRingInPlace)
    }

    @Test
    fun theInfoOfThePlaceTellsTheTurns() {
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.infoSays("info_ring_in_place", "botão para baixo", "sem mexer no anel de manobras")
    }

    @Test
    fun everyKindOfDataHasItsOwnInfoThatOpensWithoutTickingTheBox() {
        // Every card ticked to begin with (the default is two now), so each box is seen to stay as it was.
        settings = settings.copy(infoRing = true, infoRingData = RingData.ALL)
        compose.waitForIdle()
        val texts = HashSet<String>()
        for (data in RingData.entries) {
            val tag = "info_ring_data_${data.name.lowercase()}"
            compose.onNodeWithTag(tag + "_info", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("info_sheet").assertExists()
            val shown = compose.onAllNodes(hasText("", substring = true).and(hasAnyAncestor(hasTestTag("info_sheet"))))
                .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("|") { it.text }
            assertTrue("the info of $data says something of its own: $shown", shown.length > 40 && texts.add(shown))
            compose.onNodeWithTag("info_sheet_ok").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(tag).performScrollTo().assertIsOn()
        }
        assertEquals(RingData.ALL, settings.infoRingData)
    }

    @Test
    fun withEveryKindUntickedALineSaysTheRingStaysOff() {
        settings = settings.copy(infoRing = true, infoRingData = RingData.ALL)
        compose.waitForIdle()
        // At the checkbox, at the right: the middle of a row with a long title may be its (i).
        for (data in RingData.entries) compose.onNodeWithTag("info_ring_data_${data.name.lowercase()}").performScrollTo().tapSwitch()
        assertEquals(0, settings.infoRingData)
        compose.onNodeWithTag("info_ring_data_none").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theCarouselIsOnWithSevenSecondsAndTheTimeGoesFiveToThirty() {
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_carousel").performScrollTo().assertIsOn()
        compose.onNodeWithTag("info_ring_seconds_value").performScrollTo().assertTextEquals("7 s")
        compose.onNodeWithTag("info_ring_seconds_plus").performClick()
        assertEquals(8, settings.infoRingSeconds)
        compose.onNodeWithTag("info_ring_seconds_value").assertTextEquals("8 s")
        repeat(3) { compose.onNodeWithTag("info_ring_seconds_minus").performClick() }
        assertEquals(5, settings.infoRingSeconds)
        compose.onNodeWithTag("info_ring_seconds_minus").performClick()
        assertEquals("not under five", 5, settings.infoRingSeconds)
        settings = settings.copy(infoRingSeconds = 30)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_seconds_plus").performClick()
        assertEquals("not over thirty", 30, settings.infoRingSeconds)
    }

    @Test
    fun withTheCarouselOffThereIsNoTimeToSet() {
        settings = settings.copy(infoRing = true)
        compose.waitForIdle()
        compose.onNodeWithTag("info_ring_carousel").performScrollTo().tapSwitch()
        assertEquals(false, settings.infoRingCarousel)
        compose.onNodeWithTag("info_ring_seconds_value").assertDoesNotExist()
        compose.onNodeWithTag("info_ring_carousel").tapSwitch()
        assertEquals(true, settings.infoRingCarousel)
        compose.onNodeWithTag("info_ring_seconds_value").performScrollTo().assertIsDisplayed()
    }
}
