package com.reau.app.settings

import com.reau.app.settings.Terms.Block
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terms of use: the three texts say the version the code says, the file at the root of the repository is the same
 * text, the blocks come out as the screen draws them, and the text says nothing of any country's law (a rule of the project;
 * only the AGPL's own two passages, quoted word for word, name "applicable law").
 */
class TermsTest {
    private val portuguese = File("src/main/assets/${Terms.ASSET_PT}").readText()
    private val english = File("src/main/assets/${Terms.ASSET_EN}").readText()
    private val spanish = File("src/main/assets/${Terms.ASSET_ES}").readText()
    private val rootPortuguese = File("../TERMS_OF_USE.pt-br.md").readText()
    private val rootEnglish = File("../TERMS_OF_USE.md").readText()
    private val rootSpanish = File("../TERMS_OF_USE.es.md").readText()

    @Test
    fun `a rider who accepted nothing, or an older version, must accept`() {
        assertTrue(Terms.needsAcceptance(0))
        assertTrue(Terms.needsAcceptance(Terms.VERSION - 1))
        assertFalse(Terms.needsAcceptance(Terms.VERSION))
        assertFalse(Terms.needsAcceptance(Terms.VERSION + 1))
    }

    @Test
    fun `the screen opens in Portuguese for a Portuguese app and in English for any other language`() {
        assertTrue(Terms.opensInPortuguese("pt"))
        assertTrue(Terms.opensInPortuguese("pt-BR"))
        assertFalse(Terms.opensInPortuguese("en"))
        assertFalse(Terms.opensInPortuguese("es"))
        assertFalse(Terms.opensInPortuguese(""))
    }

    @Test
    fun `the screen opens on the tab of the app's language`() {
        assertEquals(Terms.TAB_PT, Terms.tabOf("pt-BR"))
        assertEquals(Terms.TAB_ES, Terms.tabOf("es"))
        assertEquals(Terms.TAB_ES, Terms.tabOf("ES-mx"))
        assertEquals(Terms.TAB_EN, Terms.tabOf("en"))
        assertEquals("any other language reads English", Terms.TAB_EN, Terms.tabOf("fr"))
    }

    @Test
    fun `all three texts carry the version the code carries`() {
        assertTrue(portuguese, "Versão ${Terms.VERSION}, de " in portuguese)
        assertTrue(english, "Version ${Terms.VERSION}, " in english)
        assertTrue(spanish, "Versión ${Terms.VERSION}, " in spanish)
    }

    @Test
    fun `the three files at the root of the repository are the three texts, one language each`() {
        assertEquals(portuguese, rootPortuguese)
        assertEquals(english, rootEnglish)
        assertEquals(spanish, rootSpanish)
    }

    @Test
    fun `the blocks come out as the screen draws them`() {
        val blocks = Terms.parse("# Title\n\nA line\nand more.\n\n## Part\n\n- one\n- two\n\n> quoted\n---\nlast")
        assertEquals(
            listOf(
                Block.Title("Title"), Block.Paragraph("A line and more."), Block.Heading("Part"),
                Block.Bullet("one"), Block.Bullet("two"), Block.Quote("quoted"), Block.Paragraph("last"),
            ),
            blocks,
        )
    }

    @Test
    fun `the real texts have the same sections in the same order`() {
        fun headings(text: String) = Terms.parse(text).filterIsInstance<Block.Heading>().map { it.text.substringBefore(' ') }.filter { it.first().isDigit() }
        assertEquals(headings(portuguese), headings(english))
        assertEquals(headings(portuguese), headings(spanish))
        assertTrue(headings(portuguese).size >= 10)
    }

    @Test
    fun `the three texts have the same shape, block by block, so none says more than the others`() {
        fun shape(text: String) = Terms.parse(text).map { it::class.simpleName }
        assertEquals(shape(portuguese), shape(english))
        assertEquals(shape(portuguese), shape(spanish))
        // The plain-words paragraph that follows each of the AGPL's two passages is in all three (it was only in the Portuguese one).
        for ((text, lead) in listOf(portuguese to "Em português:", english to "In plain words:", spanish to "En español:")) {
            assertEquals("'$lead' paragraphs", 2, Regex("(?m)^" + Regex.escape(lead)).findAll(text).count())
        }
    }

    @Test
    fun `the texts quote the AGPL's two passages word for word`() {
        val license = File("../LICENSE").readText().replace(Regex("\\s+"), " ")
        for (text in listOf(portuguese, english, spanish)) {
            val quotes = Terms.parse(text).filterIsInstance<Block.Quote>().map { it.text }
            assertEquals(2, quotes.size)
            for (quote in quotes) assertTrue("not in the LICENSE: ${quote.take(60)}", quote in license)
        }
    }

    @Test
    fun `outside the AGPL's own words the texts name no country's law`() {
        val forbidden = Regex(
            """\b(lei|leis|legisla\w*|jurisdi\w*|foro|tribunal\w*|consumidor\w*|brasil\w*|brazil\w*|laws?|legal|legally|courts?|jurisdiction|statut\w*|ley|leyes|legal\w*|jurisdic\w*|tribunales|colombia\w*)\b""",
            RegexOption.IGNORE_CASE,
        )
        for ((name, text) in listOf("pt" to portuguese, "en" to english, "es" to spanish)) {
            val mine = Terms.parse(text).filter { it !is Block.Quote }.joinToString("\n") {
                when (it) {
                    is Block.Title -> it.text
                    is Block.Heading -> it.text
                    is Block.Paragraph -> it.text
                    is Block.Bullet -> it.text
                    is Block.Quote -> ""
                }
            }
            assertEquals("$name: ${forbidden.findAll(mine).map { it.value }.toList()}", 0, forbidden.findAll(mine).count())
        }
    }

    @Test
    fun `the terms speak of no service the app does not have, and no longer speak of editions`() {
        // Version 3 (28/10/2026) still stands. Since 0.3.0 (one app) there are no editions, and since the removal of the accessibility service
        // (03/11/2026) the terms have no clause about one: the version and the date did not change, the clause only said less than the app does.
        val all = mapOf("pt" to portuguese, "en" to english, "es" to spanish)
        for ((lang, text) in all) {
            assertFalse("$lang: no service left", Regex("(?i)accessib|acessibil|accesibil").containsMatchIn(text))
            assertFalse("$lang: no voice apps named", listOf("WhatsApp", "Telegram", "Signal").any { it in text })
            assertFalse("$lang: no edition left", Regex("(?i)edi[cç][ãa]o|edici[oó]n|edition|\\blite\\b|completa").containsMatchIn(text))
        }
    }
}
