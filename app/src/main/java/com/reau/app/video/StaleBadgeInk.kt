package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

/**
 * The discreet badge laid on the dash's video when Android Auto's picture is stuck ([com.reau.app.aa.StaleImage]):
 * a small dark pill with amber letters, at the bottom centre where the glass is widest, so the rider sees at a glance
 * that the picture is the last one and not a live map.
 */
object StaleBadgeInk {
    private const val AMBER = 0xFFFFB300.toInt()
    private const val PILL = 0xE6000000.toInt()

    /** The letters' height in the dash's 526×300 frame. */
    private const val LETTERS = 20f
    private const val PAD_X = 14f
    private const val PAD_Y = 6f

    /** The gap between the pill and the frame's bottom edge. */
    private const val BOTTOM_GAP = 10

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = LETTERS
        color = AMBER
    }

    /** The badge saying [text], on a transparent bitmap of its box. */
    fun render(text: String): Bitmap {
        val ink = paint()
        val width = ceil(ink.measureText(text) + 2 * PAD_X).toInt()
        val height = ceil(ink.fontMetrics.let { it.descent - it.ascent } + 2 * PAD_Y).toInt()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val radius = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL })
        canvas.drawText(text, PAD_X, PAD_Y - ink.fontMetrics.ascent, ink)
        return bitmap
    }

    /** The badge's top-left corner for a box of [width]×[height]: centred, just above the frame's bottom edge. */
    fun corner(width: Int, height: Int): Pair<Int, Int> = (DashGlass.FRAME_W - width) / 2 to (DashGlass.FRAME_H - height - BOTTOM_GAP)
}
