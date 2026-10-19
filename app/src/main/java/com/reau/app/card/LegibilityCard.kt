package com.reau.app.card

import com.reau.app.video.DashGlass
import com.reau.app.video.RectPx
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The legibility card of the card mode, laid out in the pixels of the dash's 526×300 frame:
 * what the rider looks at on the real glass to learn what can be read and where the useful area ends.
 *
 * - Texts in the marked sizes ([SIZES], px of text size), each saying its own size, smallest on top, each as far
 *   left as the round glass allows on its rows.
 * - Thin lines of 1, 2 and 3 px and arrows of 16, 24 and 32 px, on the right of the dome.
 * - The outline of the glass: its circle, of which only the top arc falls inside the frame.
 * - Five patches of plain colour in the two top corners, where the rider never sees them: they are the control
 *   positions a test decodes the video at ([Card.controlPoints]).
 *
 * Laid out first around a smaller, assumed dome; since the glass was measured ([DashGlass]) the same
 * texts, lines and arrows sit well inside it, and only the outline, the patches and the black points moved.
 *
 * Everything but the outline and the patches is wholly on the glass. Text widths come from [layout]'s measure:
 * the real font's on the phone, a stand-in in the JVM tests.
 */
object LegibilityCard {
    /** Free pixels between any two things on the card. */
    const val GAP = 4

    /** The sample street after each text size, when no other is given (the phone gives it in the rider's language: `card_legibility_sample`). */
    const val SAMPLE = "Street 300 m"

    val SIZES = listOf(8, 10, 12, 14, 16, 20, 24)
    val LINE_WIDTHS = listOf(1, 2, 3)
    val ARROW_SIZES = listOf(16, 24, 32)

    /** Opaque ARGB colours. */
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val OUTLINE_GREY = 0xFF808080.toInt()

    /** A text: drawn at [box]'s left with its baseline one size below the top; [box] is one line of it. */
    data class Label(val text: String, val sizePx: Int, val box: RectPx) {
        val baseline: Int get() = box.y + sizePx
    }

    /** A horizontal line [widthPx] thick. */
    data class Line(val widthPx: Int, val box: RectPx)

    /** An arrow pointing up, filling its square [box]. */
    data class Arrow(val sizePx: Int, val box: RectPx)

    data class Patch(val name: String, val color: Int, val rect: RectPx)

    data class Outline(val centerX: Int, val centerY: Int, val radius: Int, val usableBottom: Int)

    /** A pixel whose colour in the decoded video must be [color]. */
    data class ControlPoint(val x: Int, val y: Int, val color: Int)

    data class Card(val labels: List<Label>, val lines: List<Line>, val arrows: List<Arrow>, val patches: List<Patch>, val outline: Outline) {
        /** The middle of each patch, and black where nothing at all is drawn. */
        fun controlPoints(): List<ControlPoint> =
            patches.map { ControlPoint(it.rect.x + it.rect.w / 2, it.rect.y + it.rect.h / 2, it.color) } +
                BLACK_POINTS.map { (x, y) -> ControlPoint(x, y, BLACK) }
    }

    private const val TOP = 24
    private const val EDGE_MARGIN = 3
    private const val LINES_X = 290
    private const val LINE_LENGTH = 80
    private const val FIRST_LINE_Y = 50
    private const val LINE_STEP = 10
    private const val ARROWS_X = 290
    private const val ARROWS_BOTTOM = 140
    private const val ARROW_GAP = 8
    private const val PATCH = 24

    /** Off the glass and away from every drawing: in the two top corners, outside the circle. */
    private val BLACK_POINTS = listOf(60 to 60, 466 to 60, 100 to 20, 426 to 20)

    /** Second row of the top corners' patches. */
    private const val PATCH_ROW_2 = 30

    private val PATCHES = listOf(
        Patch("red", 0xFFFF0000.toInt(), RectPx(0, 0, PATCH, PATCH)),
        Patch("green", 0xFF00FF00.toInt(), RectPx(DashGlass.FRAME_W - PATCH, 0, PATCH, PATCH)),
        Patch("blue", 0xFF0000FF.toInt(), RectPx(0, PATCH_ROW_2, PATCH, PATCH)),
        Patch("yellow", 0xFFFFFF00.toInt(), RectPx(DashGlass.FRAME_W - PATCH, PATCH_ROW_2, PATCH, PATCH)),
        Patch("white", WHITE, RectPx(PATCH_ROW_2, 0, PATCH, PATCH)),
    )

    /** The card, with [measure] giving the width in px of a text at a text size; each text says its size, then [sample]. */
    fun layout(measure: (String, Int) -> Int, sample: String = SAMPLE): Card {
        var y = TOP
        val labels = SIZES.map { size ->
            val text = "$size $sample"
            val h = size * 5 / 4
            val x = leftmostOnGlass(y, y + h) + EDGE_MARGIN
            Label(text, size, RectPx(x, y, measure(text, size), h)).also { y += h + GAP }
        }
        val lines = LINE_WIDTHS.mapIndexed { i, w -> Line(w, RectPx(LINES_X, FIRST_LINE_Y + i * LINE_STEP, LINE_LENGTH, w)) }
        var x = ARROWS_X
        val arrows = ARROW_SIZES.map { size ->
            Arrow(size, RectPx(x, ARROWS_BOTTOM - size, size, size)).also { x += size + ARROW_GAP }
        }
        val outline = Outline(DashGlass.CENTER_X, DashGlass.CENTER_Y, DashGlass.RADIUS, DashGlass.USABLE_BOTTOM)
        return Card(labels, lines, arrows, PATCHES, outline)
    }

    /** The first whole pixel of the glass on every row from [top] to [bottom]. */
    private fun leftmostOnGlass(top: Int, bottom: Int): Int {
        fun halfChord(row: Int): Double {
            val dy = row - DashGlass.CENTER_Y.toDouble()
            return sqrt(DashGlass.RADIUS.toDouble() * DashGlass.RADIUS - dy * dy)
        }
        val half = min(halfChord(top), halfChord(bottom))
        return DashGlass.CENTER_X - floor(half).toInt()
    }
}
