package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.BuildConfig
import com.reau.app.R
import com.reau.app.update.ReleaseCheck
import com.reau.app.update.SigningCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Check for updates" block of the settings: what it says for each answer, the button for the release only when it is newer. */
@RunWith(AndroidJUnit4::class)
class UpdateRowTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    private var installed by mutableStateOf<List<String>?>(null)
    private var checks = 0
    private val opened = mutableListOf<String>()

    private fun show() = compose.setContent { AppTheme { UpdateRow(state, { checks++ }, installed) { opened += it } } }

    private fun status() = compose.onNodeWithTag("update_status")

    @Test
    fun beforeTheTapItSaysTheVersionAndThatNothingIsAskedByItself() {
        show()
        status().assertIsDisplayed().assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        compose.onNodeWithTag("update_note").assertTextContains("api.github.com", substring = true)
        compose.onNodeWithTag("update_open").assertDoesNotExist()
        assertEquals("nothing is asked until the tap", 0, checks)
        compose.onNodeWithTag("update_check").performClick()
        assertEquals(1, checks)
    }

    @Test
    fun theCheckButtonIsInTheMiddleOfTheCard_andWithTheOtherOneTheTwoAreCentredTogether() {
        show()
        val card = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val one = compose.onNodeWithTag("update_check").fetchSemanticsNode().boundsInRoot
        val tolerance = 6f * context.resources.displayMetrics.density
        assertTrue("the button's centre ${one.center.x} is the card's ${card.center.x}", kotlin.math.abs(one.center.x - card.center.x) <= tolerance)
        state = UpdateState.Done(ReleaseCheck.Result.Latest("v99.0.0", "https://github.com/Murkiriel/reau/releases/tag/v99.0.0"))
        compose.waitForIdle()
        val left = compose.onNodeWithTag("update_check").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("update_open").fetchSemanticsNode().boundsInRoot
        val middle = (left.left + right.right) / 2f
        assertTrue("the two buttons' middle $middle is the card's ${card.center.x}", kotlin.math.abs(middle - card.center.x) <= tolerance)
    }

    @Test
    fun aNewerReleaseIsToldAndItsPageOpensOnlyOnTheTap() {
        state = UpdateState.Done(ReleaseCheck.Result.Latest("v99.0.0", "https://github.com/Murkiriel/reau/releases/tag/v99.0.0"))
        show()
        status().assertTextContains("v99.0.0", substring = true)
        assertEquals("nothing opens by itself", emptyList<String>(), opened)
        compose.onNodeWithTag("update_open").assertIsDisplayed().performClick()
        assertEquals(listOf("https://github.com/Murkiriel/reau/releases/tag/v99.0.0"), opened)
    }

    @Test
    fun aDebugBuildOfTheLatestVersionSaysItIsADevelopmentBuild() {
        // The tests run the debug build, whose name carries "-dev": against the version it is based on it is not "up to date".
        val base = BuildConfig.VERSION_NAME.substringBefore('-')
        state = UpdateState.Done(ReleaseCheck.Result.Latest("v$base", "https://github.com/Murkiriel/reau/releases/tag/v$base"))
        show()
        status().assertTextContains(context.getString(R.string.update_dev, BuildConfig.VERSION_NAME, "v$base"))
        compose.onNodeWithTag("update_open").assertDoesNotExist()
    }

    private fun failureSays(result: ReleaseCheck.Result, text: Int) {
        state = UpdateState.Done(result)
        show()
        status().assertTextContains(context.getString(text))
        compose.onNodeWithTag("update_open").assertDoesNotExist()
    }

    @Test
    fun offlineSaysGitHubCouldNotBeReached() = failureSays(ReleaseCheck.Result.Failed(ReleaseCheck.Why.OFFLINE), R.string.update_offline)

    @Test
    fun limitedSaysToTryAgainInAWhile() = failureSays(ReleaseCheck.Result.Failed(ReleaseCheck.Why.LIMITED), R.string.update_limited)

    @Test
    fun aServerOrAnswerThatIsNotAReleaseSaysItDidNotAnswerAsExpected() {
        failureSays(ReleaseCheck.Result.Failed(ReleaseCheck.Why.BAD_ANSWER), R.string.update_failed)
    }

    @Test
    fun noReleaseSaysNothingIsPublishedYet() = failureSays(ReleaseCheck.Result.NoRelease, R.string.update_none)

    @Test
    fun whileAskingItSaysSo() {
        state = UpdateState.Checking
        show()
        status().assertTextContains(context.getString(R.string.update_checking))
    }

    @Test
    fun theSignatureOfTheInstalledApkIsShownAndSaidWhetherItIsTheProjects() {
        val fingerprint = SigningCheck.fingerprint("a certificate".toByteArray())
        installed = listOf(fingerprint)
        show()
        compose.onNodeWithTag("update_sign_installed").assertIsDisplayed().assertTextContains(SigningCheck.display(fingerprint))
        compose.onNodeWithTag("update_sign_expected").assertIsDisplayed().assertTextContains(SigningCheck.display(SigningCheck.EXPECTED_SHA256))
        val says = when (SigningCheck.verdict(SigningCheck.EXPECTED_SHA256, installed, BuildConfig.DEBUG)) {
            SigningCheck.Verdict.MATCH -> R.string.update_sign_match
            SigningCheck.Verdict.DIFFERENT -> R.string.update_sign_differ
            SigningCheck.Verdict.DEBUG_BUILD -> R.string.update_sign_debug
            SigningCheck.Verdict.NOT_SET -> R.string.update_sign_unset
            SigningCheck.Verdict.UNREADABLE -> R.string.update_sign_unreadable
        }
        compose.onNodeWithTag("update_sign_verdict").assertIsDisplayed().assertTextContains(context.getString(says))
    }

    @Test
    fun whenAndroidDoesNotGiveTheSignatureItSaysSoAndShowsNoNumber() {
        installed = null
        show()
        compose.onNodeWithTag("update_sign_verdict").assertTextContains(context.getString(R.string.update_sign_unreadable))
        compose.onNodeWithTag("update_sign_installed").assertDoesNotExist()
    }
}
