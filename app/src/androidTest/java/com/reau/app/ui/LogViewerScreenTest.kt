package com.reau.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Ver o registro": the log files newest first, and a tap shows the end of one, saying how
 * much of its start was left out; back goes from the file to the list, then out.
 */
@RunWith(AndroidJUnit4::class)
class LogViewerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "log-viewer-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private var back = 0
    private var shared = 0

    @Before
    fun setUp() {
        File(dir, "ride-20261009-080000.log").apply {
            writeText((1..12_000).joinToString("") { "08:00:00.000  [ui] line $it\n" })
            setLastModified(2_000)
        }
        File(dir, "crash-20261008-080000.txt").apply { writeText("an old trace\n"); setLastModified(1_000) }
        compose.setContent { LogViewerScreen(dir, onBack = { back++ }, onShare = { shared++ }) }
    }

    @Test
    fun theFilesNewestFirstAndOneOpensAtItsEnd() {
        compose.onNodeWithTag("log_file_0").assertTextContains("ride-20261009-080000.log", substring = true)
        compose.onNodeWithTag("log_file_1").assertTextContains("crash-20261008-080000.txt", substring = true)
        compose.onNodeWithTag("log_file_0").performClick()
        // 12,000 lines are ~350 KB: more than the 256 KB shown, so the start is left out and it says so.
        compose.onNodeWithTag("log_skipped").assertIsDisplayed().assertTextContains("KB", substring = true)
        compose.onNodeWithTag("log_text").performScrollTo().assertTextContains("line 12000", substring = true)
    }

    @Test
    fun backGoesFromTheFileToTheListThenOut() {
        compose.onNodeWithTag("log_file_1").performClick()
        compose.onNodeWithTag("log_text").assertTextContains("an old trace", substring = true)
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("log_file_0").assertIsDisplayed()
        assertEquals(0, back)
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
    }

    /** The redesign: how many files and how big on top, sharing from the top bar, the end's title. */
    @Test
    fun theListSaysHowManyAndHowBigAndSharesFromTheTop() {
        compose.onNodeWithTag("log_summary").assertTextContains("2 arquivos", substring = true)
        compose.onNodeWithTag("share_logs").performClick()
        assertEquals(1, shared)
        compose.onNodeWithTag("log_viewer_title").assertTextContains("Registro")
        compose.onNodeWithTag("log_file_1").performClick()
        compose.onNodeWithTag("log_viewer_title").assertTextContains("Fim do arquivo")
    }
}
