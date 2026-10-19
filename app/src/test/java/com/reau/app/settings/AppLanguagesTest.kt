package com.reau.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** A escolha de idioma: como uma etiqueta de idioma do Android vira uma das tres opcoes do app. */
class AppLanguagesTest {
    @Test
    fun `qualquer portugues e portugues, em qualquer caixa`() {
        for (tag in listOf("pt", "pt-BR", "pt-PT", "PT", "pt_BR", "Pt-br")) {
            assertEquals(tag, AppLanguage.PT, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `qualquer ingles e ingles`() {
        for (tag in listOf("en", "en-US", "en-GB", "EN", "en_AU")) {
            assertEquals(tag, AppLanguage.EN, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `sem etiqueta, ou com um idioma que o app nao tem, vale o do telefone`() {
        for (tag in listOf(null, "", "  ", "\t", "es", "fr-FR", "de", "ja", "zh-Hans", "xx")) {
            assertEquals("'$tag'", AppLanguage.AUTO, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `as etiquetas de cada idioma voltam ao mesmo idioma`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguages.fromTag(language.tag))
        }
    }

    @Test
    fun `as etiquetas gravadas sao as que o Android entende`() {
        assertEquals("", AppLanguage.AUTO.tag)
        assertEquals("pt-BR", AppLanguage.PT.tag)
        assertEquals("en", AppLanguage.EN.tag)
    }
}
