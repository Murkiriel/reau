package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.video.PhonePreview
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen that shows Android Auto running in the background: reached from the main
 * screen while the projection runs, it hands its surface to the compositor's preview ([PhonePreview]) while it is
 * open and takes it back when it closes.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoScreenTest {
    @get:Rule val compose = createComposeRule()

    private val seen = CopyOnWriteArrayList<PhonePreview.Shown?>()
    private val target: (PhonePreview.Shown?) -> Unit = { seen += it }

    @After
    fun tearDown() {
        PhonePreview.detach(target)
        PhonePreview.hide()
    }

    @Test
    fun theWayToTheScreenIsThereOnlyWhileTheProjectionRuns() {
        var state by mutableStateOf(MainUiState(Projection()))
        var opened = 0
        compose.setContent { MainScreen(state, {}, {}, {}, MainExtras(onShowAndroidAuto = { opened++ })) }
        // The redesign: stopped, the shortcut is there but faded, and a tap says why instead of opening.
        compose.onNodeWithTag("show_aa_screen").performScrollTo().performClick()
        assertEquals(0, opened)
        state = MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY))
        compose.onNodeWithTag("show_aa_screen").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opened)
        // With Android Auto up and the dash not connected yet, the screen is there too.
        state = MainUiState(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.CONNECTING))
        compose.onNodeWithTag("show_aa_screen").performScrollTo().assertIsDisplayed()
    }

    private val touches = CopyOnWriteArrayList<com.reau.app.aa.PhoneTouch>()
    private val touchTarget: (com.reau.app.aa.PhoneTouch, PhonePreview.Shown?) -> Unit = { t, _ -> touches += t }

    @Test
    fun withTheSwitchOnATapOnThePictureGoesToAndroidAutoAndWithItOffNothingDoes() {
        // Interact with Android Auto from the phone, behind a switch (off by default).
        PhonePreview.attachTouch(touchTarget)
        try {
            var touch by mutableStateOf(false)
            compose.setContent { AndroidAutoScreen(onBack = {}, touch = touch) }
            compose.onNodeWithTag("aa_screen_picture").performTouchInput { click() }
            compose.waitForIdle()
            assertTrue("switch off: nothing sent, got $touches", touches.isEmpty())
            touch = true
            compose.waitForIdle()
            compose.onNodeWithTag("aa_screen_picture").performTouchInput { click() }
            compose.waitUntil(3_000) { touches.any { it.action == com.reau.app.aa.PhoneTouch.Action.UP } }
            assertEquals(com.reau.app.aa.PhoneTouch.Action.DOWN, touches.first().action)
            assertEquals(com.reau.app.aa.PhoneTouch.Action.UP, touches.last().action)
        } finally {
            PhonePreview.detachTouch(touchTarget)
        }
    }

    @Test
    fun theScreenHandsItsSurfaceToThePreviewAndTakesItBackWhenItCloses() {
        PhonePreview.attach(target)
        var open by mutableStateOf(true)
        var back = 0
        compose.setContent { if (open) AndroidAutoScreen(onBack = { back++ }) }
        compose.waitUntil(5_000) { seen.any { it != null } }
        val shown = seen.last { it != null }!!
        assertTrue("a real size: ${shown.width}x${shown.height}", shown.width > 0 && shown.height > 0)
        compose.onNodeWithTag("aa_screen_back").performClick()
        assertEquals(1, back)
        open = false
        compose.waitUntil(5_000) { seen.last() == null }
    }

    // ── the dash's mask over the picture, on and off ──

    private val whole = com.reau.app.video.FrameFit.wholeVisible(com.reau.app.video.RectPx(0, 0, 800, 480))

    @Test
    fun theMaskSwitchSaysWhatTheRiderPickedAndOffDrawsNothing() {
        val picked = CopyOnWriteArrayList<Boolean>()
        compose.setContent { AndroidAutoScreen(onBack = {}, mask = AaScreenMask(on = false, mapping = whole, sourceW = 800, sourceH = 480) { picked += it }) }
        compose.onNodeWithTag("aa_screen_mask").assertDoesNotExist()
        compose.onNodeWithTag("aa_screen_mask_switch").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), picked.toList())
    }

    @Test
    fun withTheMaskOnTheDashRingIsDrawnWhereTheDashPutsIt() {
        compose.setContent { AndroidAutoScreen(onBack = {}, mask = AaScreenMask(on = true, mapping = whole, sourceW = 800, sourceH = 480) {}) }
        val shot = compose.onNodeWithTag("aa_screen_mask").assertExists().captureToImage().asAndroidBitmap()
        val m = checkNotNull(com.reau.app.video.DashMask.of(whole, 800, 480, shot.width, shot.height))
        val ringWidth = com.reau.app.ui.components.RING_WIDTH * m.bubble.r / com.reau.app.video.DashMask.BUBBLE_R
        // the gold ring, half its width inside the circle's edge, on the right
        val gold = shot.getPixel((m.bubble.cx + m.bubble.r - ringWidth / 2).toInt(), m.bubble.cy.toInt())
        assertTrue("gold ring at the bubble's edge: ${Integer.toHexString(gold)}", android.graphics.Color.red(gold) > 170 && android.graphics.Color.blue(gold) < 140)
        // the ring's light inside, where the dash's bubble covers the picture
        val inside = shot.getPixel(m.bubble.cx.toInt(), m.bubble.cy.toInt())
        assertTrue("light inside the ring: ${Integer.toHexString(inside)}", android.graphics.Color.red(inside) > 200 && android.graphics.Color.blue(inside) > 200)
    }
}
