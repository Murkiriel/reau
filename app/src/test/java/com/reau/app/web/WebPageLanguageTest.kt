package com.reau.app.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page speaks two languages and picks one by itself: Portuguese when the browser's first language is Portuguese, English
 * for any other (and for none). The page is plain JavaScript with no engine on the JVM, so the dictionary and the rule are read
 * out of the file: the keys, the placeholders, the pattern of a Portuguese tag (run here with the same regular expression).
 */
class WebPageLanguageTest {
    private val page = File("src/main/assets/web/index.html").readText().replace("\r\n", "\n")

    /** `key: "text"` lines of the `en` or the `pt` block of the dictionary. */
    private fun dictionary(language: String): Map<String, String> {
        val block = Regex("""\n    $language: \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
            ?: error("the page has no '$language' block")
        return Regex("""^\s+(\w+): "(.*)",?$""", RegexOption.MULTILINE).findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en get() = dictionary("en")
    private val pt get() = dictionary("pt")

    /** The pattern of a Portuguese tag, as the page has it. */
    private fun portugueseTag(): Regex {
        val source = Regex("""var PT_TAG = /(.*)/i;""").find(page)?.groupValues?.get(1) ?: error("the page has no PT_TAG")
        return Regex(source, RegexOption.IGNORE_CASE)
    }

    @Test
    fun `every text is in English and in Portuguese, with the same keys and the same place for a number`() {
        assertTrue("the dictionary is not empty", en.size >= 10)
        assertEquals(en.keys, pt.keys)
        for ((key, text) in en) {
            assertTrue("$key is empty in en", text.isNotBlank())
            assertTrue("$key is empty in pt", pt.getValue(key).isNotBlank())
            assertEquals("$key: the {n} place", text.contains("{n}"), pt.getValue(key).contains("{n}"))
            if (key != "title") assertTrue("$key was left in English in pt", text != pt.getValue(key))
        }
    }

    @Test
    fun `the English texts do not look Portuguese and the Portuguese ones are the page's old texts`() {
        for ((key, text) in en) {
            assertFalse("$key has accents: $text", Regex("[áâãàéêíóôõúç]", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse("$key says a Portuguese word: $text", Regex("""\b(você|celular|tente|não|aparelho|tela)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        assertEquals("Muitas tentativas erradas (429). Espere uns 5 minutos.", pt["too_many"])
        assertTrue(pt.getValue("pin_wrong").contains("PIN errado"))
        assertTrue(pt.getValue("sound_hint").contains("Toque na tela para ligar o som"))
        assertTrue(en.getValue("sound_hint").startsWith("Tap"))
    }

    @Test
    fun `every key the page asks for is in the dictionary`() {
        val asked = buildSet {
            Regex("""\bt\(([^()]*?)[,)]""").findAll(page).forEach { call -> Regex(""""(\w+)"""").findAll(call.groupValues[1]).forEach { add(it.groupValues[1]) } }
            Regex("""data-i18n(?:-aria)?="(\w+)"""").findAll(page).forEach { add(it.groupValues[1]) }
        }
        assertTrue("the page asks for something", asked.size >= 10)
        for (key in asked) {
            assertTrue("$key is not in en", key in en)
            assertTrue("$key is not in pt", key in pt)
        }
        // and the dictionary has nothing the page never asks for
        for (key in en.keys) assertTrue("$key is never used", key in asked || key == "title")
    }

    @Test
    fun `no visible text is left outside the dictionary`() {
        val outside = page.replace(Regex("""\n    pt: \{\n.*?\n    \}""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<link rel="icon"[^>]*>"""), "")
        assertFalse("Portuguese letters outside the pt block", Regex("[áâãàéêíóôõúçÁÉÓÚ]").containsMatchIn(outside))
        for (old in listOf("Limpar", "Apagar", "Conferindo", "Desconectado", "Toque na tela", "Muitas tentativas")) assertFalse(old, outside.contains(old))
    }

    @Test
    fun `a Portuguese browser gets Portuguese and any other language, or none, gets English`() {
        val portuguese = portugueseTag()
        for (tag in listOf("pt", "pt-BR", "PT-br", "pt-PT", "pt_BR")) assertTrue(tag, portuguese.containsMatchIn(tag))
        for (tag in listOf("en", "en-US", "es", "es-419", "fr-PT", "ptx", "pl", "")) assertFalse("'$tag'", portuguese.containsMatchIn(tag))
    }

    @Test
    fun `the language comes from the browser's list, the first one counts, and the default is English`() {
        assertTrue(page.contains("navigator.languages") && page.contains("navigator.language"))
        assertTrue("an empty list gives English", page.contains("""return "en";"""))
        assertTrue("the first language decides", page.contains("""if (tag) return PT_TAG.test(tag) ? "pt" : "en";"""))
        assertTrue("the choice is made with the browser's list", page.contains("var lang = pickLang("))
        assertTrue("every text goes through the chosen language", page.contains("T[lang][key]"))
    }

    @Test
    fun `the chosen language is also the page's lang and title, and the markup starts in English`() {
        assertTrue(page.contains("<html lang=\"en\">"))
        assertTrue(page.contains("document.documentElement.lang = lang === \"pt\" ? \"pt-BR\" : \"en\";"))
        assertTrue(page.contains("document.title = t(\"title\");"))
        assertTrue("the translating is done before anything is shown", page.indexOf("\n  translate();") in 1 until page.indexOf("function say("))
        assertTrue("the keypad's labels are named for a screen reader in the chosen language", page.contains("data-i18n-aria=\"key_clear\"") && page.contains("data-i18n-aria=\"key_delete\""))
    }
}
