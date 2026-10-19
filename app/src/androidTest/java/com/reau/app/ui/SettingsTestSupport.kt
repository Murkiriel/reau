package com.reau.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp

// The redesign's settings: the steps the settings tests share.

/** "Depuração" starts folded: opens it, to reach its rows. */
internal fun ComposeContentTestRule.openDebug() {
    onNodeWithTag("debug").performScrollTo().performClick()
    waitForIdle()
}

/** The (i) of the row [tag] opens the sheet with the whole explanation: each of [texts] is in it; "Entendi" closes it. */
internal fun ComposeContentTestRule.infoSays(tag: String, vararg texts: String) {
    onNodeWithTag("${tag}_info").performScrollTo().performClick()
    waitForIdle()
    for (text in texts) onNodeWithText(text, substring = true).assertExists()
    onNodeWithTag("info_sheet_ok").performClick()
    waitForIdle()
    onNodeWithTag("info_sheet").assertDoesNotExist()
}

/**
 * A tap on a switch row where the rider taps to switch it: at its right, on the switch. The middle of a short row may
 * be the (i), whose 48 dp touch area opens the explanation instead (as it should).
 */
internal fun SemanticsNodeInteraction.tapSwitch(): SemanticsNodeInteraction = performTouchInput {
    click(Offset(width - SWITCH_FROM_RIGHT.dp.toPx(), centerY))
}

private const val SWITCH_FROM_RIGHT = 42
