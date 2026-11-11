package com.reau.app.devtools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arrow test never exists in a release (the 11/11/2026 request): the stub answers none, the main source only reads it, and holds nothing of its screen. */
class ArrowTrialAbsenceInReleaseTest {
    private val root = File("src")
    private fun mainSources(): List<File> = File(root, "main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(path: String) = File(root, path).readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `the release ArrowTrial can only answer none`() {
        val stub = code("release/java/com/reau/app/devtools/ArrowTrial.kt")
        assertTrue(stub, "fun byte(): Int? = null" in stub)
        assertFalse(stub, "next" in stub || "previous" in stub || "stop" in stub || "StateFlow" in stub || "var " in stub || "RideDiagnostics" in stub)
        assertEquals("the only function", 1, Regex("""\bfun\b""").findAll(stub).count())
    }

    @Test
    fun `the main source reads the byte and nothing else of the arrow test`() {
        val uses = mainSources().flatMap { f -> Regex("""ArrowTrial\.(\w+)""").findAll(f.readText()).map { it.groupValues[1] to f.name }.toList() }
        assertEquals(uses.toString(), listOf("byte" to "DashSession.kt", "byte" to "DashSession.kt"), uses)
    }

    @Test
    fun `nothing of the arrow test's screen or log is in the main source`() {
        for (f in mainSources()) {
            val body = f.readText()
            assertFalse("${f.name} has the arrow test's tags", "debug_arrow" in body)
            assertFalse("${f.name} steps the arrow test", "ArrowTrial.next" in body || "ArrowTrial.previous" in body || "ArrowTrial.stop" in body)
        }
    }

    @Test
    fun `the screen of the arrow test is only in the debug source`() {
        assertTrue(File(root, "debug/java/com/reau/app/devtools/DebugTriggers.kt").readText().contains("debug_arrow_next"))
        assertFalse(File(root, "release/java/com/reau/app/devtools/DebugTriggers.kt").readText().contains("debug_arrow"))
    }
}
