package com.reau.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The site (docs/) holds together in its three languages: the same pages, the same questions under the same ids, no link to a page, a file
 * or an anchor that is not there, every picture with its text, each page saying its own language, and the sitemap listing every page.
 * Read from the files, as [SpanishDocsTest] does; what the pages say is not judged here.
 */
class SiteIntegrityTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
    private val docs = File(root, "docs")
    private val languages = listOf("pt", "en", "es")
    private fun pages(language: String) = File(docs, language).listFiles { f -> f.extension == "html" }!!.sortedBy { it.name }
    private val all: List<File> get() = languages.flatMap(::pages) + File(docs, "index.html")
    private fun ids(html: String) = Regex("""\sid="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `the three languages have the same pages`() {
        val names = languages.associateWith { pages(it).map(File::getName) }
        assertEquals(names.getValue("pt"), names.getValue("en"))
        assertEquals(names.getValue("pt"), names.getValue("es"))
        assertTrue("some pages", names.getValue("pt").size >= 4)
    }

    @Test
    fun `every page has the same ids, in the same order, in the three languages, and none twice`() {
        for (page in pages("pt").map(File::getName)) {
            val byLanguage = languages.associateWith { ids(File(docs, "$it/$page").readText()) }
            assertEquals("$page: en", byLanguage.getValue("pt"), byLanguage.getValue("en"))
            assertEquals("$page: es", byLanguage.getValue("pt"), byLanguage.getValue("es"))
            assertEquals("$page: an id twice", byLanguage.getValue("pt").distinct(), byLanguage.getValue("pt"))
        }
        assertTrue("the questions are there", ids(File(docs, "pt/faq.html").readText()).count { Regex("""s\d+-q\d+""").matches(it) } > 50)
    }

    @Test
    fun `no link or picture points to a file or an anchor that is not there`() {
        val broken = mutableListOf<String>()
        for (page in all) {
            val html = page.readText()
            val targets = Regex("""(?:href|src)="([^"]+)"""").findAll(html).map { it.groupValues[1] }
                .filterNot { it.startsWith("http") || it.startsWith("mailto:") || it.startsWith("data:") || it.startsWith("javascript:") }
            for (target in targets) {
                val path = target.substringBefore('#').substringBefore('?')
                val anchor = target.substringAfter('#', "")
                val file = if (path.isEmpty()) page else File(page.parentFile, path).let { if (it.isDirectory) File(it, "index.html") else it }
                if (!file.isFile) broken += "${page.relativeTo(docs)}: $target"
                else if (anchor.isNotEmpty() && file.extension == "html" && anchor !in ids(file.readText())) broken += "${page.relativeTo(docs)}: $target (anchor)"
            }
        }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun `every picture has its text, and each page says its own language`() {
        for (page in all) {
            val html = page.readText()
            val bare = Regex("""<img\b[^>]*>""").findAll(html).map { it.value }.filterNot { Regex("""\salt="""").containsMatchIn(it) }.toList()
            assertEquals("${page.relativeTo(docs)}: pictures with no alt", emptyList<String>(), bare)
        }
        val said = mapOf("pt" to "pt-BR", "en" to "en", "es" to "es")
        for (language in languages) for (page in pages(language)) {
            val lang = Regex("""<html[^>]*\slang="([^"]+)"""").find(page.readText())?.groupValues?.get(1)
            assertEquals("${page.relativeTo(docs)}", said.getValue(language), lang)
        }
    }

    @Test
    fun `the sitemap lists every page of the three languages once, and no page that is not there`() {
        val listed = Regex("""<loc>https://murkiriel\.github\.io/reau/([^<]*)</loc>""").findAll(File(docs, "sitemap.xml").readText())
            .map { it.groupValues[1] }.toList()
        val expected = listOf("") + languages.flatMap { language -> pages(language).map { "$language/" + if (it.name == "index.html") "" else it.name } }
        assertEquals(expected.sorted(), listed.sorted())
    }

    @Test
    fun `the questions page says when it was last brought up to date, the same day in the three languages`() {
        val days = mapOf("pt" to Regex("""atualizado em (\d{2})/(\d{2})/2026"""), "en" to Regex("""updated on (\d{1,2}) (\w{3}) 2026"""), "es" to Regex("""actualizada el (\d{1,2}) (\w{3}) 2026"""))
        // The page says it three times (its description twice, for search engines, and its opening line): the three must agree.
        val day = days.mapValues { (language, pattern) ->
            pattern.findAll(File(docs, "$language/faq.html").readText()).map { it.groupValues[1].toInt() }.toList()
        }
        for (language in languages) assertEquals("$language: said three times, the same day", 1 to 3, day.getValue(language).let { it.distinct().size to it.size })
        assertEquals(day.getValue("pt").first(), day.getValue("en").first())
        assertEquals(day.getValue("pt").first(), day.getValue("es").first())
    }
}
