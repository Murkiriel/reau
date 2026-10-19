package com.reau.app.card

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.video.DashGlass
import com.reau.app.video.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The card mode's pictures, drawn with the phone's own font: the legibility card as [LegibilityCard] lays it out,
 * and a picture of the rider's placed by [PicturePlacement]. Read back pixel by pixel, before any codec.
 */
@RunWith(AndroidJUnit4::class)
class CardPainterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card = LegibilityCard.layout(CardPainter::measure)

    @Test
    fun theLegibilityCardIsTheDashsFrame() {
        val bitmap = CardPainter.legibility()
        assertEquals(DashGlass.FRAME_W to DashGlass.FRAME_H, bitmap.width to bitmap.height)
    }

    @Test
    fun withTheRealFontEverythingIsStillWhollyOnTheGlassAndApart() {
        val boxes = card.labels.map { it.box } + card.lines.map { it.box } + card.arrows.map { it.box }
        for (box in boxes) assertEquals("$box", 1.0, DashGlass.visibleFraction(box), 0.0)
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val (a, b) = boxes[i] to boxes[j]
                val apart = a.right + LegibilityCard.GAP <= b.x || b.right + LegibilityCard.GAP <= a.x ||
                    a.bottom + LegibilityCard.GAP <= b.y || b.bottom + LegibilityCard.GAP <= a.y
                assertTrue("$a and $b", apart)
            }
        }
    }

    @Test
    fun theControlPointsHaveTheirColours() {
        val bitmap = CardPainter.legibility()
        for (p in card.controlPoints()) assertEquals("(${p.x},${p.y})", p.color, bitmap.getPixel(p.x, p.y))
    }

    /** Text is drawn smoothed, as Waze's is: its pixels are bright, not all pure white. Lines and arrows are solid. */
    @Test
    fun everyTextLineAndArrowIsDrawnInWhite() {
        val bitmap = CardPainter.legibility()
        for (label in card.labels) {
            val bright = count(bitmap, label.box) { Color.red(it) >= 160 && Color.green(it) >= 160 && Color.blue(it) >= 160 }
            assertTrue("${label.text} drawn in ${label.box}: $bright bright pixels", bright >= label.sizePx)
        }
        for (arrow in card.arrows) assertTrue("arrow ${arrow.sizePx} in ${arrow.box}", whiteIn(bitmap, arrow.box) >= arrow.sizePx * arrow.sizePx / 4)
        for (line in card.lines) assertEquals("line ${line.widthPx} is solid", line.box.w * line.box.h, whiteIn(bitmap, line.box))
    }

    @Test
    fun nothingIsDrawnOutsideItsPlace() {
        val bitmap = CardPainter.legibility()
        val places = card.labels.map { it.box } + card.lines.map { it.box } + card.arrows.map { it.box } + card.patches.map { it.rect }
        val stray = mutableListOf<String>()
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == Color.BLACK) continue
                if (places.any { inside(x, y, grow(it, 2)) } || onOutline(x, y)) continue
                stray += "($x,$y)"
            }
        }
        assertTrue("${stray.size} pixels drawn out of place: ${stray.take(10)}", stray.isEmpty())
    }

    @Test
    fun theOutlineIsDrawnOnTheCircleAndOnTheLine() {
        val bitmap = CardPainter.legibility()
        // The left and right ends of the circle's horizontal diameter, and its top; no line under the picture.
        assertEquals(LegibilityCard.OUTLINE_GREY, bitmap.getPixel(DashGlass.CENTER_X - DashGlass.RADIUS, DashGlass.CENTER_Y))
        assertEquals(LegibilityCard.OUTLINE_GREY, bitmap.getPixel(DashGlass.CENTER_X + DashGlass.RADIUS - 1, DashGlass.CENTER_Y))
        assertEquals(LegibilityCard.OUTLINE_GREY, bitmap.getPixel(DashGlass.CENTER_X, 0))
        assertEquals("the bottom row is not a line", Color.BLACK, bitmap.getPixel(100, DashGlass.FRAME_H - 1))
        assertEquals("opaque black elsewhere", Color.BLACK, bitmap.getPixel(DashGlass.CENTER_X, 100))
    }

    @Test
    fun aRidersPictureIsPlacedWholeAndBlackAroundIt() {
        val placed = CardPainter.picture(quadrants(800, 480))
        val at = PicturePlacement.inFrame(800, 480)
        assertEquals(DashGlass.FRAME_W to DashGlass.FRAME_H, placed.width to placed.height)
        assertEquals(Color.RED, placed.getPixel(at.x + at.w / 4, at.y + at.h / 4))
        assertEquals(Color.GREEN, placed.getPixel(at.x + 3 * at.w / 4, at.y + at.h / 4))
        assertEquals(Color.BLUE, placed.getPixel(at.x + at.w / 4, at.y + 3 * at.h / 4))
        assertEquals(Color.YELLOW, placed.getPixel(at.x + 3 * at.w / 4, at.y + 3 * at.h / 4))
        assertEquals("black beside it", Color.BLACK, placed.getPixel(at.x / 2, DashGlass.FRAME_H / 2))
    }

    @Test
    fun aRidersPictureIsReadFromItsUriAndAMissingOneIsNull() {
        val file = File(context.cacheDir, "card_test.png")
        // Four times the frame each way: read whole, it would be well over the limit.
        file.outputStream().use { quadrants(2104, 1200).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val loaded = CardPainter.load(context, Uri.fromFile(file))
        assertNotNull(loaded)
        assertEquals("same shape", 2104.0 / 1200, loaded!!.width.toDouble() / loaded.height, 0.01)
        assertTrue("not kept larger than twice the frame: ${loaded.width}", loaded.width <= 2 * DashGlass.FRAME_W)
        assertEquals(Color.RED, loaded.getPixel(loaded.width / 4, loaded.height / 4))
        file.delete()

        assertNull(CardPainter.load(context, Uri.fromFile(File(context.cacheDir, "no_such_card.png"))))
    }

    private fun quadrants(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        val paint = Paint()
        for ((i, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
            paint.color = color
            val left = (i % 2) * w / 2f
            val top = (i / 2) * h / 2f
            canvas.drawRect(left, top, left + w / 2f, top + h / 2f, paint)
        }
    }

    private fun whiteIn(bitmap: Bitmap, box: RectPx): Int = count(bitmap, box) { it == Color.WHITE }

    private fun count(bitmap: Bitmap, box: RectPx, which: (Int) -> Boolean): Int {
        var n = 0
        for (y in box.y until box.bottom) for (x in box.x until box.right) if (which(bitmap.getPixel(x, y))) n++
        return n
    }

    private fun onOutline(x: Int, y: Int): Boolean {
        val fromCentre = hypot(x + 0.5 - DashGlass.CENTER_X, y + 0.5 - DashGlass.CENTER_Y)
        val onCircle = abs(fromCentre - DashGlass.RADIUS) <= 2
        val onLine = abs(y - DashGlass.USABLE_BOTTOM) <= 2 && fromCentre <= DashGlass.RADIUS + 2
        return onCircle || onLine
    }

    private fun grow(r: RectPx, by: Int) = RectPx(r.x - by, r.y - by, r.w + 2 * by, r.h + 2 * by)

    private fun inside(x: Int, y: Int, r: RectPx) = x >= r.x && x < r.right && y >= r.y && y < r.bottom
}
