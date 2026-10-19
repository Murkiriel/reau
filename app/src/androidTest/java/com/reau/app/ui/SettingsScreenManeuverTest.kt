package com.reau.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The maneuver data is always on and has no row in the settings any more; the
 * group "No painel" keeps, in this order, the phone's state, who calls, the song and the album's picture.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenManeuverTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(PlanSettings.PLAN, SettingsActions({}, {}, {}, {})) }
    }

    @Test
    fun theManeuverRowIsGoneAndTheOthersComeInTheirOrder() {
        compose.onNodeWithTag("maneuver_data").assertDoesNotExist()
        compose.onNodeWithTag("maneuver_data_locked").assertDoesNotExist()
        val order = listOf("dash_phone_status", "dash_caller", "dash_music", "dash_album_art", "dash_route_extras")
        // The whole column is composed: with the last row scrolled to, every row's position is read in the same scroll state.
        compose.onNodeWithTag(order.last()).performScrollTo()
        val tops = order.map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        org.junit.Assert.assertEquals("the phone's state, who calls, the song, the cover, top to bottom", tops.sorted(), tops)
    }

    /** The ring switches went: hiding the ring never worked on the dash. */
    @Test
    fun theRingSwitchesAreGone() {
        compose.onNodeWithTag("dash_phone_status").performScrollTo()
        compose.onNodeWithTag("hide_ring_media").assertDoesNotExist()
        compose.openDebug()
        compose.onNodeWithTag("ring_always_off").assertDoesNotExist()
    }
}
