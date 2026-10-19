package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every setting that is "experimental" or "de teste" has its letters in red, to say it is not yet the app's standard
 * and may be removed. A row is marked by what its own title and line say, so a new experiment is red by writing
 * "Experimental" or "(teste)" in its text, and nothing has to be remembered elsewhere.
 */
class ExperimentalMarkTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()

    private fun text(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("sem o texto $name")

    @Test
    fun `a row that says experimental or test is marked`() {
        assertTrue(ExperimentalMark.marks("Capa do álbum", "Experimental: a imagem no cartão de música"))
        assertTrue(ExperimentalMark.marks("Protocolo 1.7 do Android Auto (teste)", null))
        assertTrue(ExperimentalMark.marks("SPS de baixa latência (teste)", ""))
        assertTrue("any case", ExperimentalMark.marks("EXPERIMENTAL", null))
    }

    @Test
    fun `the app's own rows are not marked, not even by words that only look alike`() {
        assertFalse(ExperimentalMark.marks("Registro de diagnóstico", "Gravando no celular"))
        assertFalse(ExperimentalMark.marks("Gravar o vídeo enviado ao painel", "Para depuração"))
        assertFalse("plural, a section's line, not a setting that is a test", ExperimentalMark.marks("Depuração", "Testes, registros e gravações"))
        assertFalse(ExperimentalMark.marks("Ligar o servidor de rede", null))
        assertFalse(ExperimentalMark.marks(null, null))
    }

    @Test
    fun `exactly the rows the app calls experimental are red today`() {
        val marked = Regex("""<string name="([a-z_0-9]+)">""").findAll(strings).map { it.groupValues[1] }
            .filter { it.endsWith("_sub") || it.endsWith("_title") }
            .filter { ExperimentalMark.marks(text(it)) }
            .toSet()
        // The album cover works well, it is not experimental any more; only the real route stays red.
        assertEquals(setOf("route_extras_sub"), marked)
    }

    @Test
    fun `there is no legend on the settings, the red letters stand for themselves`() {
        // The red line at the top explaining the red toggles is gone.
        assertFalse(strings.contains("name=\"experimental_legend\""))
    }

    @Test
    fun `the section is called Depuração and nothing is called advanced any more`() {
        assertEquals("Depuração", text("debug_title"))
        assertFalse(ExperimentalMark.marks(text("debug_title"), text("debug_sub")))
        assertFalse("nothing is called Avançado any more in the app's texts", strings.contains("Avançado", ignoreCase = true))
    }
}
