package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.AppPermissions
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The permissions page, listing every permission and why it is used: each row says what the permission is, whether it
 * is granted now, and why REau uses it; the special accesses come first, with buttons to the places they are switched.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var back = 0
    private var appSettings = 0
    private var accessibility = 0
    private val asked = mutableListOf<AppPermissions.Ask>()
    private val askedAll = mutableListOf<List<String>>()

    private val rows = AppPermissions.rows(
        requested = listOf(
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
            "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        ),
        granted = { it == "android.permission.INTERNET" },
        accessibilityOn = false,
        batteryExempt = true,
    )

    private var shown by mutableStateOf(rows)

    @Before
    fun setUp() {
        compose.setContent {
            PermissionsScreen(
                shown,
                PermissionsActions(
                    onBack = { back++ },
                    onOpenAppSettings = { appSettings++ },
                    onOpenAccessibility = { accessibility++ },
                    onAsk = { asked += it },
                    onAskAll = { askedAll += it },
                ),
                sdk = 34,
            )
        }
    }

    private fun row(permission: String) = compose.onNodeWithTag("permission_$permission").performScrollTo()

    /** The redesign: the why is folded until the row is tapped, and never cut. */
    private fun opened(permission: String) = row(permission).performClick()

    @Test
    fun eachRowSaysWhatItIsWhetherItIsGrantedAndWhy() {
        opened(AppPermissions.ACCESSIBILITY).assertTextContains("Acessibilidade", substring = true)
            .assertTextContains("Desligado", substring = true)
            .assertTextContains("encaixe", substring = true)
        row(AppPermissions.BATTERY).assertTextContains("Ligado", substring = true)
        opened("android.permission.RECORD_AUDIO").assertTextContains("Microfone", substring = true)
            .assertTextContains("Não liberada", substring = true)
            .assertTextContains("Gemini", substring = true)
            .assertTextContains("android.permission.RECORD_AUDIO", substring = true)
        opened("android.permission.INTERNET").assertTextContains("Liberada", substring = true)
            .assertTextContains("127.0.0.1", substring = true)
        opened("com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION").assertTextContains("biblioteca", substring = true)
    }

    @Test
    fun accessibilitySwitchedOnButNotRunningSaysSoAndHowToFixIt() {
        shown = AppPermissions.rows(emptyList(), { false }, accessibilityOn = false, batteryExempt = true, accessibilityStuck = true)
        opened(AppPermissions.ACCESSIBILITY).assertTextContains("Parado", substring = true)
            .assertTextContains("desligue e ligue de novo", substring = true)
    }

    /**
     * On a clean install Android blocked the switch ("Configuração restrita"); the row says how to
     * free it, and a way leads to REau's app info, where the ⋮ has "Permitir configurações restritas".
     */
    @Test
    fun restrictedAccessibilitySaysHowToFreeItAndLeadsToTheAppInfo() {
        shown = AppPermissions.rows(emptyList(), { false }, accessibilityOn = false, batteryExempt = true, accessibilityRestricted = true)
        opened(AppPermissions.ACCESSIBILITY).assertTextContains("Permitir configurações restritas", substring = true)
        compose.onNodeWithTag("permissions_allow_restricted").performScrollTo().performClick()
        assertEquals(1, appSettings)
    }

    @Test
    fun withoutTheRestrictionThereIsNoWayToIt() {
        compose.onNodeWithTag("permissions_open_accessibility").performScrollTo()
        compose.onNodeWithTag("permissions_allow_restricted").assertDoesNotExist()
    }

    @Test
    fun whatIsNotGrantedHasAButtonToAskForIt() {
        // A button to ask for the permissions.
        opened("android.permission.RECORD_AUDIO")
        compose.onNodeWithTag("ask_android.permission.RECORD_AUDIO").performScrollTo().performClick()
        opened(AppPermissions.ACCESSIBILITY)
        compose.onNodeWithTag("ask_${AppPermissions.ACCESSIBILITY}").performScrollTo().performClick()
        assertEquals(listOf(AppPermissions.Ask.Runtime(listOf("android.permission.RECORD_AUDIO")), AppPermissions.Ask.Accessibility), asked)
        opened("android.permission.INTERNET")
        compose.onNodeWithTag("ask_android.permission.INTERNET").assertDoesNotExist()
        opened(AppPermissions.BATTERY)
        compose.onNodeWithTag("ask_${AppPermissions.BATTERY}").assertDoesNotExist() // exempt already
    }

    @Test
    fun askingForAllThatIsMissingIsOneButtonAtTheTop() {
        compose.onNodeWithTag("ask_all").performScrollTo().assertTextContains("(1)", substring = true).performClick()
        assertEquals(listOf(listOf("android.permission.RECORD_AUDIO")), askedAll)
    }

    @Test
    fun theSectionsSeparateTheKinds() {
        compose.onNodeWithTag("permissions_section_SPECIAL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("permissions_section_ASKED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("permissions_section_INSTALL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("permissions_section_LIBRARY").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theButtonsGoBackAndToWhereThePermissionsAreSwitched() {
        // The way to the accessibility settings is in the special accesses' group, before the next one.
        val way = compose.onNodeWithTag("permissions_open_accessibility").performScrollTo().fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("permissions_section_ASKED").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("the accessibility's way is above the next group", way.bottom <= next.top)
        compose.onNodeWithTag("permissions_open_accessibility").performScrollTo().performClick()
        assertEquals("it asks first", 0, accessibility)
        compose.onNodeWithTag("accessibility_confirm").performClick()
        compose.onNodeWithTag("permissions_open_app_settings").performScrollTo().performClick()
        compose.onNodeWithTag("back").performClick() // the fixed top bar
        assertEquals(1, accessibility)
        assertEquals(1, appSettings)
        assertEquals(1, back)
    }

    @Test
    fun eachGroupCountsWhatIsGranted() {
        compose.onNodeWithTag("permissions_section_INSTALL").performScrollTo().assertTextContains("1/1", substring = true)
        compose.onNodeWithTag("permissions_section_ASKED").performScrollTo().assertTextContains("0/", substring = true)
    }
}
