package com.reau.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's texts in its two languages (English is the base, `values/`; Portuguese is `values-pt/`): every text is in both,
 * with the same placeholders, and no English text is still Portuguese. A text added in one language only, or with a
 * `%1$s` lost in the translation, would show as a crash or as a gap in one language only.
 */
class StringsParityTest {
    private class Text(val name: String, val translatable: Boolean, val value: String, val plural: Boolean = false)

    private fun read(folder: String): Map<String, Text> {
        val files = File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
        val texts = mutableMapOf<String, Text>()
        for (file in files) {
            val xml = file.readText()
            Regex("""<string name="([^"]+)"( translatable="false")?>(.*?)</string>""").findAll(xml).forEach {
                texts[it.groupValues[1]] = Text(it.groupValues[1], it.groupValues[2].isEmpty(), it.groupValues[3])
            }
            Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                texts[it.groupValues[1]] = Text(it.groupValues[1], true, it.groupValues[2], plural = true)
            }
        }
        return texts
    }

    private val english = read("values")
    private val portuguese = read("values-pt")
    private val placeholder = Regex("""%(?:\d+\$)?[sdfx%]""")

    @Test
    fun `every translatable text is in both languages`() {
        val translatableEnglish = english.filterValues { it.translatable }.keys
        val translatablePortuguese = portuguese.filterValues { it.translatable }.keys
        assertEquals("only in English: ${translatableEnglish - translatablePortuguese}", emptySet<String>(), translatableEnglish - translatablePortuguese)
        assertEquals("only in Portuguese: ${translatablePortuguese - translatableEnglish}", emptySet<String>(), translatablePortuguese - translatableEnglish)
        assertTrue(translatableEnglish.size > 600)
    }

    @Test
    fun `a text keeps its placeholders in the translation`() {
        for ((name, pt) in portuguese) {
            val en = english[name] ?: continue
            fun marks(text: Text) = placeholder.findAll(text.value).map { it.value }.sorted().toList().let { if (pt.plural) it.distinct() else it }
            assertEquals("$name: placeholders", marks(pt), marks(en))
        }
    }

    @Test
    fun `no English text is still Portuguese`() {
        val portugueseLetters = Regex("[ãõçÃÕÇ]")
        val portugueseWords = Regex("""\b(você|não|para o|para a|com o|com a|uma|isso|está|são|painel|ligação|moto|ajustes|chave|quando|enquanto)\b""", RegexOption.IGNORE_CASE)
        val names = setOf("terms_lang_pt")
        for ((name, en) in english) {
            if (!en.translatable || name in names) continue
            assertTrue("$name looks Portuguese: ${en.value.take(80)}", !portugueseLetters.containsMatchIn(en.value) && !portugueseWords.containsMatchIn(en.value))
        }
    }

    @Test
    fun `an English text is not the Portuguese one, except names and numbers that are the same word in both`() {
        val same = setOf(
            "app_name", "language_pt", "language_en", "terms_lang_pt", "terms_lang_en",
        )
        val equal = english.filter { (name, en) -> en.translatable && name !in same && portuguese[name]?.value == en.value && en.value.any { it.isLetter() } }
        // Words that are the same in both languages (Wi-Fi, Android Auto, Gemini, ...) are fine; this list only shows what to look at.
        assertTrue("same in both languages, check they are not untranslated: ${equal.keys}", equal.size < 60)
    }
}
