package com.reau.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The top bar is the same height on every screen, the start screen's: the main screen's bar ("REau" centred) and an
 * inner screen's (back arrow and title) match.
 */
@RunWith(AndroidJUnit4::class)
class TopBarHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theMainBarAndAnInnerBarAreTheSameHeight() {
        compose.setContent {
            Column {
                REauTopBar()
                REauTopBar("O REau e os seus dados", onBack = {})
            }
        }
        val main = compose.onAllNodesWithTag("top_bar")[0]
        val inner = compose.onAllNodesWithTag("top_bar")[1]
        main.assertHeightIsAtLeast(TopBarHeight)
        inner.assertHeightIsAtLeast(TopBarHeight)
        val hMain = main.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val hInner = inner.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertEquals(hMain.value, hInner.value, 0.5f)
    }
}
