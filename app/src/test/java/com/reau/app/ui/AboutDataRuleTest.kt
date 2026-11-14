package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "O REau e os seus dados" (the owner's rule, 28/10/2026): every body text of the screen, the three lines on top and the text of each part, is
 * one sentence of at most 33 words, in English, Portuguese and Spanish separately. The titles do not count. The texts are found where the
 * screen uses them (a `Part(...)` or a `Summary(...)`), so a new part is held to the rule without anyone listing it.
 */
class AboutDataRuleTest {
    private val src = File("src").takeIf { it.isDirectory } ?: File("app/src")
    private val screen = File(src, "main/java/com/reau/app/ui/AboutDataScreen.kt").readText()

    private val names: List<String> =
        Regex("""Part\("[a-z]+",\s*R\.drawable\.\w+,\s*R\.string\.\w+,\s*R\.string\.(\w+)\)""").findAll(screen).map { it.groupValues[1] }.toList() +
            Regex("""Summary\(R\.drawable\.\w+,\s*R\.string\.(\w+)\)""").findAll(screen).map { it.groupValues[1] }.toList()

    private val texts: List<Triple<String, String, String>> = listOf("main").flatMap { set ->
        listOf("values", "values-pt", "values-es").flatMap { folder ->
            File(src, "$set/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().flatMap { file ->
                Regex("""<string name="(about_\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                    .filter { it.groupValues[1] in names && it.groupValues[2].isNotBlank() }.map { Triple(folder, it.groupValues[1], it.groupValues[2]) }.toList()
            }
        }
    }

    private fun words(text: String) = Regex("""\S+""").findAll(text).count()

    private fun sentences(text: String) = text.trim().split(Regex("""(?<=[.!?])["”’)]?\s+""")).count { it.isNotBlank() }

    @Test
    fun `the screen's body texts are found, three lines and seven parts, in the three languages`() {
        assertEquals("three lines on top and seven parts: $names", 10, names.size)
        for (name in names) for (folder in listOf("values", "values-pt", "values-es")) {
            assertTrue("$folder/$name", texts.any { it.first == folder && it.second == name })
        }
    }

    @Test
    fun `every body text of the screen is one sentence of at most 33 words`() {
        val over = texts.filter { sentences(it.third) > 1 || words(it.third) > 33 }.map { "${it.first}/${it.second}: ${sentences(it.third)} sentences, ${words(it.third)} words" }
        assertEquals("texts over the rule: $over", emptyList<String>(), over)
    }

    private fun string(set: String, folder: String, name: String): String? =
        File(src, "$set/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
            .firstNotNullOfOrNull { Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(it.readText())?.groupValues?.get(1) }

    /** The line under REau and its logo on the credits screen (owner, 28/10/2026): at most 2 sentences and 40 words, and it still names the brands. */
    @Test
    fun `the opening line of the credits is short, in the three languages, and still names the brands and the tap`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val text = string("main", folder, "credits_independent")!!
            assertTrue("$folder: ${sentences(text)} sentences", sentences(text) <= 2)
            assertTrue("$folder: ${words(text)} words", words(text) <= 40)
            assertTrue("$folder: the brands", "Royal Enfield" in text && "Google" in text && "Tripper" in text)
            assertTrue("$folder: the independence", Regex("independent|independente|independiente").containsMatchIn(text))
            assertTrue("$folder: the tap to copy", Regex("copy|copiar").containsMatchIn(text))
        }
    }

    /** The picture's name in the bridge on the main screen is the short name, no "test" in it (owner, 28/10/2026). */
    @Test
    fun `the default picture is called just wallpaper`() {
        assertEquals("Wallpaper", string("main", "values", "card_default"))
        assertEquals("Wallpaper", string("main", "values-pt", "card_default"))
        assertEquals("Fondo", string("main", "values-es", "card_default"))
    }
}
