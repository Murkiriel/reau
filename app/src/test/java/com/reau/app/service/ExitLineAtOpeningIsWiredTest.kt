package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the `exit` line is asked for (11/11/2026): at the app's opening, off the main thread and in the app's own process only, and at each projection's
 * start as before; and the two calls take turns. Of the text of the source: both places need a Context and Android's exit history
 * (what is told, and not twice, is held by ExitHistoryOpeningTest).
 */
class ExitLineAtOpeningIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `the app asks for the exit line when it opens, on a thread of its own, in the main process`() {
        val app = source("REauApp.kt")
        val block = app.substringAfter("if (Application.getProcessName() == packageName) {").substringBefore("\n        }\n")
        assertTrue(block, "kotlin.concurrent.thread(name = \"exit-history\")" in block && "com.reau.app.data.ExitHistory.reportPrevious(this)" in block)
    }

    @Test
    fun `the projection's start still tells what was not told`() {
        assertEquals(1, Regex("""ExitHistory\.reportPrevious\(this@ProjectionService\)""").findAll(source("service/ProjectionService.kt")).count())
    }

    @Test
    fun `two calls at once take turns, so the second finds the first one's mark`() {
        val history = source("data/ExitHistory.kt")
        assertTrue("@Synchronized\n    fun reportPrevious(context: Context)" in history)
    }
}
