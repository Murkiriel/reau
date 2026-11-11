package com.reau.app.devtools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug build's triggers (the warnings fired by hand, the faults injected) never exist in a release.
 * Held three ways: the real code is only in `src/debug` and the release source set has a stub that cannot arm anything; the code in `src/main` that
 * reaches them reads and nothing more; and nothing in `src/main` names what only the debug build has. (A release built from this tree was also
 * looked at for the debug strings: the report of the work says how.)
 */
class FaultInjectionAbsenceInReleaseTest {
    private val root = File("src")
    private fun text(path: String) = File(root, path).readText()
    private val debugDir = "debug/java/com/reau/app/devtools"
    private val releaseDir = "release/java/com/reau/app/devtools"

    private fun mainSources(): List<File> = File(root, "main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the release source set has the three stubs and nothing else`() {
        val names = File(root, releaseDir).list()!!.sorted()
        assertEquals(listOf("ArrowTrial.kt", "DebugTriggers.kt", "FaultInjection.kt"), names)
        val all = File(root, "release").walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()
        assertEquals(all.toString(), 3, all.size)
    }

    @Test
    fun `the release stubs are in the devtools package, not in the debug one that the APK audit refuses`() {
        // The first CI run failed here: the stubs were in com.reau.app.debug, and apk_audit.py refuses every class of that package in a release.
        // The three classes sit in com.reau.app.devtools (empty stubs in a release; the audit lets through those three names and no other class of it).
        for (name in listOf("ArrowTrial", "DebugTriggers", "FaultInjection")) {
            val stub = text("$releaseDir/$name.kt")
            assertTrue("$name: ${stub.lines().first()}", stub.lines().first() == "package com.reau.app.devtools")
            assertTrue(text("$debugDir/$name.kt").lines().first() == "package com.reau.app.devtools")
        }
        assertFalse(File(root, "release/java/com/reau/app/debug").exists())
    }

    @Test
    fun `the release FaultInjection arms nothing and answers no`() {
        val stub = text("$releaseDir/FaultInjection.kt")
        val code = stub.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("a stub that holds state: $code", "var " in code || "val " in code || "Set<" in code || "RideDiagnostics" in code)
        assertFalse("a stub that can arm", "fun arm" in code || "fun armedNow" in code || "fun reset" in code)
        assertTrue(code, "fun swallowDecoderOutput(): Boolean = false" in code)
        assertTrue(code, "fun muteEncoderOutput(): Boolean = false" in code)
        assertTrue(code, "fun takeFocusCycle(): Boolean = false" in code)
        assertTrue(code, "fun decoderRestarted() = Unit" in code && "fun encoderVerdictGiven() = Unit" in code)
    }

    @Test
    fun `the release DebugTriggers has no row and reaches no notice and no fault`() {
        val stub = text("$releaseDir/DebugTriggers.kt")
        val code = stub.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(code, "emptyList()" in code)
        for (word in listOf("ProjectionNotification", "FaultInjection", "ActionsRow", "RowAction", "showDashStuck", "showPhoneHot", "Settings.")) {
            assertFalse("$word in the release stub", word in code)
        }
    }

    @Test
    fun `every member of the release stubs is also in the debug ones, so the two builds are called the same way`() {
        fun members(path: String) = Regex("""fun (\w+)""").findAll(text(path)).map { it.groupValues[1] }.toSet()
        assertTrue(members("$debugDir/FaultInjection.kt").containsAll(members("$releaseDir/FaultInjection.kt")))
        assertTrue(members("$debugDir/DebugTriggers.kt").containsAll(members("$releaseDir/DebugTriggers.kt")))
        assertTrue(members("$debugDir/ArrowTrial.kt").containsAll(members("$releaseDir/ArrowTrial.kt")))
    }

    @Test
    fun `the main source asks the faults and never arms them`() {
        val allowed = setOf("swallowDecoderOutput", "decoderRestarted", "muteEncoderOutput", "encoderVerdictGiven", "takeFocusCycle")
        val used = mainSources().flatMap { f ->
            Regex("""FaultInjection\.(\w+)\(""").findAll(f.readText()).map { it.groupValues[1] to f.name }.toList()
        }.filterNot { (member, _) -> member == "Fault" && false }
        val notAllowed = used.filter { (member, _) -> member !in allowed }
        assertEquals("main arms or lists a fault: $notAllowed", emptyList<Pair<String, String>>(), notAllowed)
        assertEquals("the five places that ask (VideoDecoder 2, VideoLoop 2, AapTransport 1)", 5, used.size)
    }

    @Test
    fun `the main source reaches DebugTriggers in one place only, for its rows`() {
        val uses = mainSources().flatMap { f -> Regex("""DebugTriggers\.(\w+)""").findAll(f.readText()).map { it.groupValues[1] to f.name }.toList() }
        assertEquals(uses.toString(), listOf("rows" to "SettingsDebug.kt"), uses)
    }

    @Test
    fun `nothing in the main source holds the debug triggers' tags or texts`() {
        val debugTags = Regex(""""(debug_[a-z_]+)"""").findAll(text("$debugDir/DebugTriggers.kt")).map { it.groupValues[1] }.toSet()
        assertTrue(debugTags.toString(), debugTags.size >= 6)
        for (f in mainSources()) {
            val body = f.readText()
            for (tag in debugTags) assertFalse("${f.name} has \"$tag\"", "\"$tag\"" in body)
            assertFalse("${f.name} arms a fault", "Fault.DECODER_STALL" in body || "Fault.ENCODER_MUTE" in body || "Fault.FOCUS_CYCLE" in body)
            assertFalse("${f.name} fires a notice for the trigger", "debug trigger fired" in body || "fault injection armed" in body)
        }
    }

    @Test
    fun `the manifest of a release holds nothing of the debug source set`() {
        // The test activities are in src/debug/AndroidManifest.xml, merged into the debug build only; the main manifest names none of the debug package.
        assertFalse(text("main/AndroidManifest.xml").contains(".debug."))
    }
}
