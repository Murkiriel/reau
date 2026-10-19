package com.reau.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The "NA HORA" / "LIVE" chip of the settings: which rows carry it (the ones whose value the running projection takes
 * at once, read in LiveTuning, MainActivity.settingsChanged and LiveSides) and that it fits, whole, beside the title and
 * the (i) in both languages. The list is written out on purpose: a row that gains or loses the chip must be a decision
 * here too (docs: AJUSTES_NA_HORA.md).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenNowTest {
    @get:Rule val compose = createComposeRule()

    private val settings = REauSettings()

    /** The rows that apply at once, with their title in Portuguese and in English. */
    private val withChip = listOf(
        Row("language", "Idioma do app", "App language"),
        Row("map_theme", "Tema", "Theme"),
        Row("use_location", "Usar a localização", "Use location"),
        Row("answer_calls", "Atender ligação", "Answer calls"),
        Row("dash_phone_status", "Estado do celular", "Phone status"),
        Row("dash_album_art", "Capa do álbum", "Album art"),
        Row("dash_route_extras", "Rota real no painel", "Real route on the dash"),
        Row("ride_log", "Registro de diagnóstico", "Diagnostic log"),
        Row("detailed_log", "Registro detalhado", "Detailed log"),
        Row("log_keep", "Apagar sozinho os mais velhos que", "Automatically delete those older than"),
    )

    /** The rows that wait for the next start, are fixed, or are not settings at all (their buttons): no chip. */
    private val withoutChip = listOf(
        "dash_caller", "dash_music", "gemini", "aa_server_auto",
        "record_video", "record_sessions",
        "fps", "bitrate", "bitrate_mode", "keyframe", "maneuver_fps_profile", "speed_fps_profile",
        "open_joystick", "card_now", "view_logs", "share", "delete",
    )

    private data class Row(val tag: String, val titlePt: String, val titleEn: String)

    private fun chip(tag: String) = compose.onNodeWithTag("${tag}_now_chip", useUnmergedTree = true)

    private fun chipTags(): List<String> = compose.onAllNodes(
        SemanticsMatcher("a chip tag") { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(CHIP_SUFFIX) == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removeSuffix(CHIP_SUFFIX) }

    private fun TextLayoutResult.describe() = "$lineCount line(s), text ${size.width}x${size.height} px, constraints ${layoutInput.constraints}"

    /** Whether a line runs past the box the text was given (hasVisualOverflow answered true here for chips that fit). */
    private fun TextLayoutResult.cutAtTheSide() = (0 until lineCount).any { getLineRight(it) > size.width + 1 }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    /** The screen in a 360 dp wide column (a common phone), in the language of the run or in English. */
    private fun show(english: Boolean, widthDp: Int = 360) {
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { if (english) setLocale(Locale.ENGLISH) }
            val context = if (english) base.createConfigurationContext(config) else base
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources, LocalConfiguration provides config) {
                Box(Modifier.width(widthDp.dp).testTag("frame")) { SettingsScreen(settings, SettingsActions({}, {}, {}, {})) }
            }
        }
    }

    // ── which rows carry the chip ──────────────────────────────────────────────────────────────────────────

    @Test
    fun onlyTheSettingsThatApplyAtOnceCarryTheChip() {
        show(english = false)
        compose.openDebug()
        for (row in withChip) chip(row.tag).performScrollTo().assertIsDisplayed().assertTextEquals("NA HORA")
        for (tag in withoutChip) chip(tag).assertDoesNotExist()
        assertEquals("every chip of the screen is in the list", withChip.map { it.tag }.sorted(), chipTags().sorted())
    }

    // ── the chip fits ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theChipFitsBesideTheTitleAndTheInfoInPortuguese() = chipFits(english = false)

    @Test
    fun theChipFitsBesideTheTitleAndTheInfoInEnglish() = chipFits(english = true)

    /** The chip is one whole line, inside its row, and the title beside it is not cut (it may take a second line). */
    private fun chipFits(english: Boolean) {
        show(english)
        compose.openDebug()
        val screen = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        for (row in withChip) {
            val title = if (english) row.titleEn else row.titlePt
            val chip = chip(row.tag).performScrollTo().assertIsDisplayed()
            chip.assertTextEquals(if (english) "LIVE" else "NA HORA")
            val layout = chip.layout()
            assertEquals("${row.tag}: the chip on one line, ${layout.describe()}", 1, layout.lineCount)
            assertTrue("${row.tag}: the chip is cut, ${layout.describe()}", !layout.cutAtTheSide())
            val text = compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().assertIsDisplayed().layout()
            assertTrue("${row.tag}: the title is cut, ${text.describe()}", !text.cutAtTheSide())
            assertTrue("${row.tag}: the title takes at most two lines, ${text.describe()}", text.lineCount <= 2)
            val bounds = chip.fetchSemanticsNode().boundsInRoot
            assertTrue("${row.tag}: the chip is inside the screen: $bounds in $screen", bounds.left >= screen.left && bounds.right <= screen.right)
        }
    }

    private companion object {
        const val CHIP_SUFFIX = "_now_chip"
    }
}
