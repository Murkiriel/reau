package com.reau.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.AppPermissions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The permissions of a function switched off (answer calls and Gemini with the joystick) stay on the page as optional:
 * they say so, and do not enter "Pedir as que faltam". Written for a device; not run in the unit build.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsOptionalTest {
    @get:Rule val compose = createComposeRule()

    private val askedAll = mutableListOf<List<String>>()

    private val rows = AppPermissions.rows(
        requested = listOf(
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
        ),
        granted = { false },
        accessibilityOn = true,
        batteryExempt = true,
    )

    private fun show(functions: AppPermissions.Functions) {
        compose.setContent {
            PermissionsScreen(rows, PermissionsActions(onAskAll = { askedAll += it }), sdk = 34, functions = functions)
        }
    }

    @Test
    fun withTheSwitchesOffOnlyTheNotificationsAreAsked() {
        show(AppPermissions.Functions(answerCalls = false, gemini = false))
        compose.onNodeWithTag("ask_all").performScrollTo().assertTextContains("(1)", substring = true).performClick()
        assertEquals(listOf(listOf("android.permission.POST_NOTIFICATIONS")), askedAll)
        compose.onNodeWithTag("permission_android.permission.RECORD_AUDIO").performScrollTo()
            .assertTextContains("Opcional", substring = true)
        compose.onNodeWithTag("permission_android.permission.ANSWER_PHONE_CALLS").performScrollTo()
            .assertTextContains("Opcional", substring = true)
    }

    @Test
    fun withNothingMissingTheBulkButtonIsGone() {
        val onlyOptional = AppPermissions.rows(
            requested = listOf("android.permission.RECORD_AUDIO"), granted = { false }, accessibilityOn = true, batteryExempt = true,
        )
        compose.setContent {
            PermissionsScreen(
                onlyOptional, PermissionsActions(), sdk = 34, functions = AppPermissions.Functions(answerCalls = false, gemini = false),
            )
        }
        compose.onNodeWithTag("ask_all").assertDoesNotExist()
    }

    @Test
    fun withTheSwitchesOnAllThreeAreAsked() {
        show(AppPermissions.Functions())
        compose.onNodeWithTag("ask_all").performScrollTo().assertTextContains("(4)", substring = true)
    }
}
