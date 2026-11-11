package com.reau.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.settings.AppLanguage
import com.reau.app.settings.MapTheme
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.CompactStepper
import com.reau.app.ui.components.FineStepper
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.StatusSide
import com.reau.app.ui.components.WarningCard
import com.reau.app.ui.theme.LocalREauNight
import com.reau.app.ui.theme.REauStatusDark
import com.reau.app.ui.theme.REauStatusLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The legibility pass on a 360 x 640 dp phone ([PhoneFrame]), in both themes and with the font at 1.0 and at 1.3: Ligar in sight
 * with three warnings, the segments never cut, the counters' targets at 48 dp, the state letters in their own tones and the system
 * bars' icons for the theme in force.
 */
@RunWith(AndroidJUnit4::class)
class LegibilityScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The four ways the screen is met: dark and light, the font at 1.0 and at 1.3. */
    private val ways = listOf(Triple("escuro", true, 1f), Triple("escuro, fonte 1,3", true, 1.3f), Triple("claro", false, 1f), Triple("claro, fonte 1,3", false, 1.3f))

    private var night by mutableStateOf(true)
    private var scale by mutableStateOf(1f)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalREauNight provides night) { PhoneFrame(fontScale = scale, content = content) } }
    }

    private inline fun eachWay(check: (String) -> Unit) {
        for ((name, dark, fontScale) in ways) {
            night = dark
            scale = fontScale
            compose.waitForIdle()
            check(name)
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    // ── Ligar stays in sight ─────────────────────────────────────────────────────────────────────────────

    private val serverOffWithThreeWarnings = MainUiState(
        Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF, dash = DashLink.READY), "17.9.664004",
        startedAtMs = 0, battery = BatteryUi(exempt = false), vpnHoldsREau = true,
    )

    @Test
    fun ligarIsInSightWithThreeWarningsOnASmallPhoneAtAnyFontScaleAndTheme() {
        val state = serverOffWithThreeWarnings
        assertEquals(3, MainLook.warnings(state.projection, state.battery, state.vpnHoldsREau, false, false).size)
        show { MainScreen(state, {}, {}, {}, clock = { 61_000L }) }
        eachWay { way ->
            val button = compose.onNodeWithTag("toggle")
            button.assertIsDisplayed()
            button.assertHeightIsAtLeast(48.dp)
            val frame = compose.onNodeWithTag(PHONE_TAG).getUnclippedBoundsInRoot()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue("$way: Ligar/Desligar ends at ${bounds.bottom}, the phone at ${frame.bottom}", bounds.bottom <= frame.bottom)
            assertTrue("$way: starts at ${bounds.left}", bounds.left >= frame.left && bounds.right <= frame.right)
        }
    }

    @Test
    fun ligarIsInSightWhenStoppedAndNothingIsWrongToo() {
        show { MainScreen(MainUiState(Projection(), "17.9.664004"), {}, {}, {}) }
        eachWay { way ->
            compose.onNodeWithTag("toggle").assertIsDisplayed()
            val frame = compose.onNodeWithTag(PHONE_TAG).getUnclippedBoundsInRoot()
            assertTrue("$way", compose.onNodeWithTag("toggle").getUnclippedBoundsInRoot().bottom <= frame.bottom)
        }
    }

    // ── the segments are not cut ─────────────────────────────────────────────────────────────────────────

    @Test
    fun theLanguageAndThemeSegmentsAreNeverCutAndAre48DpTall() {
        show { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) }
        val rows = listOf(
            "language" to AppLanguage.entries.map { "language_${it.name}" },
            "map_theme" to MapTheme.entries.map { "theme_${it.name}" },
        )
        eachWay { way ->
            val frame = compose.onNodeWithTag(PHONE_TAG).getUnclippedBoundsInRoot()
            for ((row, segments) in rows) {
                compose.onNodeWithTag(row).performScrollTo()
                for (tag in segments) {
                    val segment = compose.onNodeWithTag(tag)
                    segment.assertHeightIsAtLeast(48.dp)
                    val bounds = segment.getUnclippedBoundsInRoot()
                    assertTrue("$way, $tag: ${bounds.left} to ${bounds.right} of ${frame.right}", bounds.left >= frame.left && bounds.right <= frame.right)
                    val text = compose.onNodeWithTag(tag, useUnmergedTree = true).onChild().layout()
                    assertEquals("$way, $tag is on one line", 1, text.lineCount)
                    // The text is centred in a wider layout, so its own width (right minus left) is what must fit the node.
                    val lineWidth = text.getLineRight(0) - text.getLineLeft(0)
                    assertTrue("$way, $tag is cut: its line is $lineWidth px, its box ${text.size.width}", lineWidth <= text.size.width + 1)
                }
            }
        }
    }

    // ── the counters' targets ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCountersButtonsAre48DpAndTheFineCounterStillFitsAtLargeFont() {
        show {
            AppTheme { androidx.compose.foundation.layout.Column {
                FineStepper("Densidade", "dica", 50, 0..100, {}, "fine")
                com.reau.app.ui.components.StepperRow("Segundos", "5 s", {}, {}, "compact")
            } }
        }
        eachWay { way ->
            for (tag in listOf("fine_minus10", "fine_minus", "fine_plus", "fine_plus10", "compact_minus", "compact_plus")) {
                val button = compose.onNodeWithTag(tag)
                button.assertHeightIsAtLeast(48.dp)
                button.assertWidthIsAtLeast(48.dp)
                val bounds = button.getUnclippedBoundsInRoot()
                assertTrue("$way, $tag is out of the phone: ${bounds.right}", bounds.right <= 360.dp && bounds.left >= 0.dp)
            }
            compose.onNodeWithTag("fine_value").assertIsDisplayed()
            assertEquals("$way, the value on one line", 1, compose.onNodeWithTag("fine_value").layout().lineCount)
        }
    }

    // ── the cross's labels ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCrossLabelsAreNotBrokenInsideAWordAtLargeFont() {
        val labels = mapOf(
            com.reau.app.aa.JoystickBindings.Control.UP to "Sair da navegação",
            com.reau.app.aa.JoystickBindings.Control.LEFT to "Afastar",
            com.reau.app.aa.JoystickBindings.Control.RIGHT to "Aproximar",
            com.reau.app.aa.JoystickBindings.Control.DOWN to "Anel info.",
            com.reau.app.aa.JoystickBindings.Control.CENTER to "Mídia",
        )
        show { AppTheme { com.reau.app.ui.components.DPadMap(labels, emptySet(), null, {}) } }
        eachWay { way ->
            for ((control, label) in labels) {
                val lines = compose.onNode(hasText(label), useUnmergedTree = true).layout()
                // A word that does not fit breaks into letters ("Aproxima / r"): a label has at most one line per word.
                assertTrue("$way, \"$label\" is on ${lines.lineCount} lines", lines.lineCount <= label.split(" ").size)
                assertTrue("$way, $control", lines.lineCount <= 2)
            }
        }
    }

    // ── the states' letters ──────────────────────────────────────────────────────────────────────────────

    private fun letterColor(text: String) = compose.onNode(hasText(text), useUnmergedTree = true).layout().layoutInput.style.color

    @Test
    fun theStateLettersTakeTheTonesMadeForText() {
        show {
            AppTheme { androidx.compose.foundation.layout.Column {
                StatusSide("PAINEL", "Conectado", StatusKind.OK, R.drawable.ic_ms_two_wheeler, tag = "ok_side")
                StatusSide("PAINEL", "Falhou", StatusKind.ERROR, R.drawable.ic_ms_two_wheeler, tag = "error_side")
                StatusSide("PAINEL", "Conectando", StatusKind.CONNECTING, R.drawable.ic_ms_two_wheeler, tag = "warn_side")
                WarningCard(StatusKind.CONNECTING, R.drawable.ic_ms_error, "Aviso do app", "texto", null, CardAction("Abrir", "open", {}), tag = "card")
            } }
        }
        night = true
        compose.waitForIdle()
        assertEquals(REauStatusDark.successText, letterColor("Conectado"))
        assertEquals(REauStatusDark.errorText, letterColor("Falhou"))
        assertEquals(REauStatusDark.warningText, letterColor("Conectando"))
        assertEquals("a card's title", REauStatusDark.warningText, letterColor("Aviso do app"))
        night = false
        compose.waitForIdle()
        assertEquals(REauStatusLight.successText, letterColor("Conectado"))
        assertEquals(REauStatusLight.errorText, letterColor("Falhou"))
        assertEquals(REauStatusLight.warningText, letterColor("Aviso do app"))
    }

    // ── the system bars ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theNavigationBarTakesDarkIconsOnTheLightThemeAndLightOnesOnTheDarkOne() {
        compose.setContent { CompositionLocalProvider(LocalREauNight provides night) { AppTheme { androidx.compose.material3.Text("barras") } } }
        fun lightNavigationBar(): Boolean {
            var value = false
            compose.runOnUiThread {
                val window = compose.activity.window
                value = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars
            }
            return value
        }
        fun lightStatusBar(): Boolean {
            var value = true
            compose.runOnUiThread {
                val window = compose.activity.window
                value = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
            }
            return value
        }
        night = false
        compose.waitForIdle()
        assertTrue("light theme: dark icons on the Marfim page", lightNavigationBar())
        assertFalse("the top bar is dark in the light theme: light icons", lightStatusBar())
        night = true
        compose.waitForIdle()
        assertFalse("dark theme: light icons on the Grafite page", lightNavigationBar())
        assertFalse(lightStatusBar())
    }

}
