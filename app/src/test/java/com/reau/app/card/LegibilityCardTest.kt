package com.reau.app.card

import com.reau.app.video.DashGlass
import com.reau.app.video.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legibility card: texts of marked sizes, thin lines, arrows and the dome's outline, laid
 * out in the pixels of the dash's 526×300 frame, so that what the rider sees on the glass can be measured. Here
 * text widths come from a stand-in measure; the real font is checked on the emulator (CardPainterTest).
 */
class LegibilityCardTest {
    /** Roughly Roboto's average glyph width: 0.6 of the text size, a little wider than the real thing. */
    private val measure: (String, Int) -> Int = { text, size -> (text.length * size * 0.6).toInt() }
    private val card = LegibilityCard.layout(measure)

    private fun boxes(): List<Pair<String, RectPx>> =
        card.labels.map { "label ${it.text}" to it.box } +
            card.lines.map { "line ${it.widthPx} px" to it.box } +
            card.arrows.map { "arrow ${it.sizePx} px" to it.box }

    @Test
    fun `the texts come in the marked sizes, smallest on top, each saying its own size`() {
        assertEquals(listOf(8, 10, 12, 14, 16, 20, 24), card.labels.map { it.sizePx })
        for (label in card.labels) assertTrue(label.text, label.text.startsWith("${label.sizePx} "))
        val tops = card.labels.map { it.box.y }
        assertEquals("top to bottom", tops.sorted(), tops)
    }

    @Test
    fun `a text box is as wide as the measure says and a line of its size tall`() {
        for (label in card.labels) {
            assertEquals(label.text, measure(label.text, label.sizePx), label.box.w)
            assertEquals(label.text, label.sizePx * 5 / 4, label.box.h)
            assertEquals("baseline one size below the top", label.box.y + label.sizePx, label.baseline)
        }
    }

    @Test
    fun `thin lines of one, two and three pixels`() {
        assertEquals(listOf(1, 2, 3), card.lines.map { it.widthPx })
        for (line in card.lines) assertEquals(line.widthPx, line.box.h)
        assertTrue(card.lines.all { it.box.w >= 60 })
    }

    @Test
    fun `arrows of three sizes`() {
        assertEquals(listOf(16, 24, 32), card.arrows.map { it.sizePx })
        for (arrow in card.arrows) assertEquals(RectPx(arrow.box.x, arrow.box.y, arrow.sizePx, arrow.sizePx), arrow.box)
    }

    @Test
    fun `every text, line and arrow is wholly on the glass`() {
        for ((what, box) in boxes()) {
            assertEquals("$what at $box", 1.0, DashGlass.visibleFraction(box), 0.0)
        }
    }

    @Test
    fun `nothing touches anything else`() {
        val all = boxes()
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                val (a, ra) = all[i]
                val (b, rb) = all[j]
                assertTrue("$a $ra and $b $rb", apart(ra, rb, LegibilityCard.GAP))
            }
        }
    }

    @Test
    fun `the outline is the glass's circle, with nothing covering the picture's bottom`() {
        assertEquals(DashGlass.CENTER_X to DashGlass.CENTER_Y, card.outline.centerX to card.outline.centerY)
        assertEquals(DashGlass.RADIUS, card.outline.radius)
        assertEquals(DashGlass.FRAME_H, card.outline.usableBottom)
    }

    @Test
    fun `five control patches, in different colours, all off the glass in the top corners, inside the frame`() {
        assertEquals(5, card.patches.size)
        assertEquals("distinct colours", 5, card.patches.map { it.color }.toSet().size)
        assertTrue("none black: the background is black", card.patches.none { it.color and 0xFFFFFF == 0 })
        for (patch in card.patches) {
            val r = patch.rect
            assertTrue("$r inside the frame", r.x >= 0 && r.y >= 0 && r.right <= DashGlass.FRAME_W && r.bottom <= DashGlass.FRAME_H)
            assertEquals("$r off the glass, where the rider never sees it", 0.0, DashGlass.visibleFraction(r), 0.0)
            assertTrue("$r big enough to survive the codec", r.w >= 20 && r.h >= 20)
        }
    }

    @Test
    fun `the control points are the middle of each patch and black where nothing is drawn`() {
        val points = card.controlPoints()
        for (patch in card.patches) {
            val r = patch.rect
            assertTrue(patch.name, points.any { it.x == r.x + r.w / 2 && it.y == r.y + r.h / 2 && it.color == patch.color })
        }
        val black = points.filter { it.color and 0xFFFFFF == 0 }
        assertTrue("some black points: ${black.size}", black.size >= 3)
        for (p in black) {
            for ((what, box) in boxes()) {
                assertTrue("black point (${p.x},${p.y}) away from $what", !inside(p.x, p.y, grow(box, 8)))
            }
            for (patch in card.patches) assertTrue(!inside(p.x, p.y, grow(patch.rect, 8)))
            val fromCentre = kotlin.math.hypot((p.x - DashGlass.CENTER_X).toDouble(), (p.y - DashGlass.CENTER_Y).toDouble())
            assertTrue("black point (${p.x},${p.y}) away from the circle", kotlin.math.abs(fromCentre - DashGlass.RADIUS) >= 8)
            assertEquals("black point (${p.x},${p.y}) off the glass", false, DashGlass.isVisible(p.x + 0.5, p.y + 0.5))
        }
    }

    private fun apart(a: RectPx, b: RectPx, gap: Int) =
        a.right + gap <= b.x || b.right + gap <= a.x || a.bottom + gap <= b.y || b.bottom + gap <= a.y

    private fun grow(r: RectPx, by: Int) = RectPx(r.x - by, r.y - by, r.w + 2 * by, r.h + 2 * by)

    private fun inside(x: Int, y: Int, r: RectPx) = x >= r.x && x < r.right && y >= r.y && y < r.bottom
}
