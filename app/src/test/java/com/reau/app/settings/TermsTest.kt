package com.reau.app.settings

import com.reau.app.settings.Terms.Block
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terms of use: the two texts say the version the code says, the file at the root of the repository is the same
 * text, the blocks come out as the screen draws them, and the text says nothing of any country's law (a rule of the project;
 * only the AGPL's own two passages, quoted word for word, name "applicable law").
 */
class TermsTest {
    private val portuguese = File("src/main/assets/${Terms.ASSET_PT}").readText()
    private val english = File("src/main/assets/${Terms.ASSET_EN}").readText()
    private val rootPortuguese = File("../TERMS_OF_USE.pt-br.md").readText()
    private val rootEnglish = File("../TERMS_OF_USE.md").readText()

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
    fun `both texts carry the version the code carries`() {
        assertTrue(portuguese, "Versão ${Terms.VERSION}, de " in portuguese)
        assertTrue(english, "Version ${Terms.VERSION}, " in english)
    }

    @Test
    fun `the two files at the root of the repository are the two texts, one language each`() {
        assertEquals(portuguese, rootPortuguese)
        assertEquals(english, rootEnglish)
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
        assertTrue(headings(portuguese).size >= 10)
    }

    @Test
    fun `the texts quote the AGPL's two passages word for word`() {
        val license = File("../LICENSE").readText().replace(Regex("\\s+"), " ")
        for (text in listOf(portuguese, english)) {
            val quotes = Terms.parse(text).filterIsInstance<Block.Quote>().map { it.text }
            assertEquals(2, quotes.size)
            for (quote in quotes) assertTrue("not in the LICENSE: ${quote.take(60)}", quote in license)
        }
    }

    @Test
    fun `outside the AGPL's own words the texts name no country's law`() {
        val forbidden = Regex(
            """\b(lei|leis|legisla\w*|jurisdi\w*|foro|tribunal\w*|consumidor\w*|brasil\w*|brazil\w*|laws?|legal|legally|courts?|jurisdiction|statut\w*)\b""",
            RegexOption.IGNORE_CASE,
        )
        for ((name, text) in listOf("pt" to portuguese, "en" to english)) {
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
}
