package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line that says what each permission is for, on the permissions screen, is short (the owner's rule of 28/10/2026), by the section it is in:
 * the ones a library adds, the ones granted at install and the ones asked of the rider have one sentence of at most 33 words; the special
 * accesses, two sentences of at most 67 words (but the screen's opening line keeps to one sentence and 33 words). Counted in English, Portuguese and Spanish separately. The table is the catalog in the code
 * ([com.reau.app.settings.AppPermissions]); a permission added without being classified there, or a text out of its limit, fails.
 */
class PermissionTextsTest {
    private val src = File("src").takeIf { it.isDirectory } ?: File("app/src")

    private data class Limit(val sentences: Int, val words: Int)

    private val limits = mapOf(
        "LIBRARY" to Limit(1, 33), "INSTALL" to Limit(1, 33), "ASKED" to Limit(1, 33), "SPECIAL" to Limit(2, 67),
    )

    /** The texts that follow a stricter rule than their section's: the opening line of the screen (owner, 28/10/2026): one sentence, 33 words. */
    private val strict = mapOf("permissions_intro" to Limit(1, 33))

    /** (kind, name of the "why" string) of every permission of the catalog, read from the source, and the library's own. */
    private val catalog: List<Pair<String, String>> =
        Regex("""Explained\([^,]+,\s*Kind\.(\w+),\s*R\.string\.\w+,\s*R\.string\.(perm_\w+_why)\)""")
            .findAll(File(src, "main/java/com/reau/app/settings/AppPermissions.kt").readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() +
            ("LIBRARY" to "perm_library_why")

    private val files = listOf("main").flatMap { set ->
        listOf("values", "values-pt", "values-es").flatMap { folder ->
            File(src, "$set/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().map { folder to it }
        }
    }

    private fun words(text: String) = Regex("""\S+""").findAll(text).count()

    private fun sentences(text: String) = text.trim().split(Regex("""(?<=[.!?])["”’)]?\s+""")).count { it.isNotBlank() }

    /** (language folder, name, text) of every "why" string with something in it. */
    private val texts = files.flatMap { (folder, file) ->
        Regex("""<string name="(perm_\w+_why)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .filter { it.groupValues[2].isNotBlank() }.map { Triple(folder, it.groupValues[1], it.groupValues[2]) }
    }

    @Test
    fun `every why text of the resources is classified in a section, and every section's text exists in the three languages`() {
        assertTrue("the catalog has ${catalog.size} entries", catalog.size >= 20)
        val classified = catalog.map { it.second }.toSet()
        val unclassified = texts.map { it.second }.toSet() - classified
        assertEquals("texts with no section in AppPermissions: $unclassified", emptySet<String>(), unclassified)
        for (name in classified) for (folder in listOf("values", "values-pt", "values-es")) {
            assertTrue("$folder/$name", texts.any { it.first == folder && it.second == name })
        }
    }

    @Test
    fun `a why text keeps the limit of its section, in each language, and the strictest when two sections share it`() {
        val kindsOf = catalog.groupBy({ it.second }, { it.first })
        val over = texts.filter { (_, name, _) -> name in kindsOf }.mapNotNull { (folder, name, text) ->
            val strictest = strict[name] ?: kindsOf.getValue(name).map { limits.getValue(it) }.minBy { it.words }
            val s = sentences(text)
            val w = words(text)
            "$folder/$name (${kindsOf.getValue(name)}): $s sentences, $w words".takeIf { s > strictest.sentences || w > strictest.words }
        }
        assertEquals("texts over their section's limit: $over", emptyList<String>(), over)
    }

    @Test
    fun `the opening line of the screen is one sentence of at most 33 words`() {
        for ((name, limit) in strict) for (folder in listOf("values", "values-pt", "values-es")) {
            val text = texts.firstOrNull { it.first == folder && it.second == name }?.third ?: stringsOf(folder, name)
            assertTrue("$folder/$name: ${sentences(text)} sentences", sentences(text) <= limit.sentences)
            assertTrue("$folder/$name: ${words(text)} words", words(text) <= limit.words)
        }
    }

    private fun stringsOf(folder: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File(src, "main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the counting counts what the rule means`() {
        assertEquals(1, sentences("One sentence, with a colon: and a semicolon; still one (Android 13 or newer)."))
        assertEquals(2, sentences("One. Two."))
        assertEquals(4, words("one two  three four"))
    }
}
