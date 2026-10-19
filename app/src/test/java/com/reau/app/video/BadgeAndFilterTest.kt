package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A etiqueta de "imagem parada" no quadro do painel e o filtro de reducao do compositor: so a parte que nao desenha. */
class BadgeAndFilterTest {
    @Test
    fun `a etiqueta fica centrada na largura e a dez pixels do fundo do quadro`() {
        val (x, y) = StaleBadgeInk.corner(width = 126, height = 32)
        assertEquals((DashGlass.FRAME_W - 126) / 2, x)
        assertEquals(DashGlass.FRAME_H - 32 - 10, y)
    }

    @Test
    fun `uma etiqueta de largura par fica com a mesma folga dos dois lados`() {
        val (x, _) = StaleBadgeInk.corner(width = 126, height = 32)
        assertEquals(DashGlass.FRAME_W - (x + 126), x)
    }

    @Test
    fun `uma etiqueta mais alta sobe, sem passar do fundo do quadro`() {
        val (_, low) = StaleBadgeInk.corner(width = 100, height = 20)
        val (_, high) = StaleBadgeInk.corner(width = 100, height = 40)
        assertEquals(20, low - high)
        assertTrue(low + 20 < DashGlass.FRAME_H)
    }

    @Test
    fun `o filtro atual e o bilinear sem nitidez, e so ele`() {
        assertTrue(ReductionFilter.CURRENT.isCurrent)
        assertEquals(ReductionFilter.Kernel.BILINEAR, ReductionFilter.CURRENT.kernel)
        assertEquals(0f, ReductionFilter.CURRENT.sharpen, 0f)
    }

    @Test
    fun `qualquer outro nucleo, ou nitidez, deixa de ser o atual`() {
        assertFalse(ReductionFilter(ReductionFilter.Kernel.SOFT).isCurrent)
        assertFalse(ReductionFilter(ReductionFilter.Kernel.CUBIC).isCurrent)
        assertFalse(ReductionFilter(ReductionFilter.Kernel.BILINEAR, sharpen = 0.3f).isCurrent)
        assertTrue(ReductionFilter(ReductionFilter.Kernel.BILINEAR, sharpen = 0f).isCurrent)
    }
}
