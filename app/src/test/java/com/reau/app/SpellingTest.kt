package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val WRONG = Regex("""\bReau""")

/** A name between backticks or quotation marks in a markdown line: it is being cited (history, a rule), not used. */
private val QUOTED = Regex("""`[^`]*`|"[^"]*"|“[^”]*”""")

/** True when [line] spells the app "Reau". Markdown may quote the old name; code, scripts and texts never may. */
internal fun misspelled(line: String, markdown: Boolean): Boolean {
    val text = if (markdown) line.replace(QUOTED, " ") else line
    return WRONG.containsMatchIn(text)
}

/**
 * The spelling rule: the app is "REau" or "reau", never "Reau" or any other variant, identifiers included.
 * Scans the app's sources, the scripts and the README (Gradle runs unit tests from the app module's folder). A markdown
 * line may quote the old name between backticks or quotation marks.
 */
class SpellingTest {
    private val roots = listOf(File("src"), File("../scripts"), File("../README.md"), File("../README.pt-br.md"))

    @Test
    fun `nothing is spelled Reau`() {
        val hits = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in TEXT && it.name != "SpellingTest.kt" }.toList() }
            .flatMap { file ->
                val markdown = file.extension == "md"
                file.readLines().mapIndexedNotNull { i, line -> if (misspelled(line, markdown)) "${file.path}:${i + 1}" else null }
            }
        assertEquals("REau or reau, never Reau", emptyList<String>(), hits)
    }

    @Test
    fun `a markdown line may quote the old name between backticks`() {
        assertFalse(misspelled("As classes `ReauApp` e `ReauSettings` foram renomeadas.", markdown = true))
    }

    @Test
    fun `a markdown line may quote the old name between quotation marks`() {
        assertFalse(misspelled("Grafia: só \"REau\" ou \"reau\", nunca \"Reau\".", markdown = true))
    }

    @Test
    fun `a markdown line that uses the old name outside any quote is still wrong`() {
        assertTrue(misspelled("O Reau liga o painel.", markdown = true))
        assertTrue(misspelled("O `REau` e o Reau são o mesmo app.", markdown = true))
    }

    @Test
    fun `code and scripts get no exemption even when the old name is quoted`() {
        assertTrue(misspelled("val name = \"Reau\"", markdown = false))
        assertTrue(misspelled("# `ReauApp` is the old class", markdown = false))
    }

    private companion object {
        val TEXT = setOf("kt", "kts", "xml", "md", "py", "txt")
    }
}
