package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What recomposes and what only redraws is the compiler's and the runtime's work, which only a test of the screen on a device can count.
 * What a JVM test can hold is where the source reads the things that change often: the three animations are read in a draw or layer
 * block (never at composition), the main screen's clock is a function read in the one text that shows it, and the log screen reads
 * no file while composing. A change that reads them at composition again shows up here.
 */
class ComposeReadsAreLocalTest {
    private val ui = File("src/main/java/com/reau/app/ui")

    @Test
    fun `the spinning icon, the sliding bridge and the blinking server icon are read in a draw or layer block, never at composition`() {
        val basics = File(ui, "components/Basics.kt").readText()
        val bridge = File(ui, "components/Bridge.kt").readText()
        val server = File(ui, "ServerBadge.kt").readText()
        for ((name, source) in listOf("Basics" to basics, "Bridge" to bridge, "ServerBadge" to server)) {
            assertFalse("$name reads an animation's value at composition", Regex("""label = "\w+"\)\.value""").containsMatchIn(source))
        }
        assertTrue(basics.contains("graphicsLayer { rotationZ = turn?.value ?: 0f }"))
        assertTrue(bridge.substringAfter("fun BridgeLine(").substringBefore("fun PrimaryPowerButton").contains("-(shift?.value ?: 0f).dp.toPx()"))
        assertTrue(server.contains("graphicsLayer { this.alpha = alpha() }"))
        assertFalse("the plain alpha modifier reads the value at composition", server.contains("import androidx.compose.ui.draw.alpha"))
    }

    @Test
    fun `the main screen's clock is handed down as functions, read in the texts that show it`() {
        val main = File(ui, "MainScreen.kt").readText()
        val bridge = File(ui, "components/Bridge.kt").readText()
        assertTrue(main.contains("private fun rememberElapsed(p: Projection, startedAtMs: Long?, clock: () -> Long): () -> String?"))
        assertTrue(main.contains("private fun rememberSub(p: Projection): () -> String"))
        assertTrue("the tick is read inside the returned function", main.contains("return { ComponentLogic.elapsedSince(running, startedAtMs, now.longValue) }"))
        assertTrue(main.contains("val resources = LocalResources.current"))
        assertTrue(main.contains("val (res, secs) = DashLostText.text(p.dashGoneAtMs, now.longValue)"))
        assertTrue(bridge.contains("sub: () -> String,"))
        assertTrue(bridge.contains("elapsed: () -> String?,"))
        assertTrue(bridge.contains("val text = elapsed() ?: return"))
        assertTrue(bridge.substringAfter("private fun SubLine(").contains("sub(),"))
        // The tick is a state that only the returned functions read.
        assertEquals(0, Regex("""\bnow\.longValue\b""").findAll(main.substringBefore("private fun rememberElapsed")).count())
        assertFalse(main.contains("var now by remember"))
    }

    @Test
    fun `the log screen reads its files off the main thread and draws from what was read`() {
        val log = File(ui, "LogViewerScreen.kt").readText()
        assertEquals("the files are read twice, each inside withContext(Dispatchers.IO)", 2, Regex("""withContext\(Dispatchers\.IO\)""").findAll(log).count())
        assertTrue(log.contains("LogFiles.entries(it)"))
        assertTrue(log.contains("LogFiles.tail(file, TAIL_BYTES)"))
        for (call in listOf(".length()", ".lastModified()", "LogFiles.list(", "listFiles(")) {
            assertFalse("the screen touches a file at $call", log.contains(call))
        }
        val reads = log.substringAfter("private fun FileList(").substringBefore("val stamp")
        assertTrue(reads.contains("produceState<List<LogFiles.Entry>?>(null, dir)"))
        assertTrue(log.substringAfter("private fun FileEnd(").substringBefore("val tail =").contains("produceState<TailState>(TailState.Reading, file)"))
    }
}
