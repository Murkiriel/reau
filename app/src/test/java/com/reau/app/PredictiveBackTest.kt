package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 14 logged "OnBackInvokedCallback is not enabled for the application" 17 times a log file,
 * every time a screen with a back handler came up. The app declares it ([ENABLED]) and, so that declaring it stays safe,
 * handles "back" only through Compose's `BackHandler` (the AndroidX dispatcher, which the new mode drives): with the
 * callback enabled Android no longer calls `Activity.onBackPressed()` nor sends the back key to the activity, so code
 * that waited for either would stop hearing "back".
 */
class PredictiveBackTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val manifest = File(root, "AndroidManifest.xml").readText()
    private val code = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the application declares the back callback, so Android 14 stops warning and the predictive back works`() {
        val application = manifest.substringAfter("<application").substringBefore(">")
        assertTrue(application, ENABLED in application)
        assertEquals("once", 1, Regex(Regex.escape("android:enableOnBackInvokedCallback")).findAll(manifest).count())
    }

    @Test
    fun `no code waits for the old ways of hearing back, which the new mode no longer calls`() {
        val old = listOf(
            Regex("""override\s+fun\s+onBackPressed\s*\("""),
            Regex("""override\s+fun\s+onKeyDown\s*\("""),
            Regex("""KEYCODE_BACK"""),
            Regex("""onBackPressed\s*\(\s*\)"""),
        )
        val found = code.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (old.any { it.containsMatchIn(line) }) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("these wait for back the old way: $found", emptyList<String>(), found)
    }

    @Test
    fun `back is handled by Compose's BackHandler, in the screens that need it`() {
        val users = code.filter { "BackHandler" in it.readText() }.map { it.name }.toSet()
        assertTrue(users.toString(), users.containsAll(setOf("MainActivity.kt", "AndroidAutoScreen.kt", "LogViewerScreen.kt")))
    }

    private companion object {
        const val ENABLED = """android:enableOnBackInvokedCallback="true""""
    }
}
