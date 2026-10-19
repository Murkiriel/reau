package com.reau.app.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen's title that carries the app's name ("O REau e os seus dados") gets the "au" in the brand's gold, as on the main
 * screen: the title's own text and nothing under it.
 */
class BrandTitleTest {
    private val gold = Color(0xFFE1AD01)

    @Test
    fun `the au of REau in a title is gold and the text is unchanged`() {
        val t = brandTitle("O REau e os seus dados", gold)
        assertEquals("O REau e os seus dados", t.text)
        val spans = t.spanStyles
        assertEquals(1, spans.size)
        assertEquals(4, spans[0].start)
        assertEquals(6, spans[0].end)
        assertEquals(gold, spans[0].item.color)
    }

    @Test
    fun `every REau of the title gets it`() {
        val t = brandTitle("REau e REau", gold)
        assertEquals(listOf(2 to 4, 9 to 11), t.spanStyles.map { it.start to it.end })
    }

    @Test
    fun `a title without the name is left alone`() {
        val t = brandTitle("Créditos", gold)
        assertEquals("Créditos", t.text)
        assertTrue(t.spanStyles.isEmpty())
    }
}
