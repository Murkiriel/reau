package com.reau.app.card

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.reau.app.video.DashGlass
import com.reau.app.video.RectPx
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Draws the card mode's pictures, each a 526×300 bitmap ready for the compositor: the
 * legibility card as [LegibilityCard] lays it out, with the phone's own font, or a picture of the rider's placed by
 * [PicturePlacement] on black.
 */
object CardPainter {
    private const val OUTLINE_RADIUS = DashGlass.RADIUS - 0.5f

    // Text is smoothed, as Waze's is; lines, arrows and patches are solid, so their pixels are exactly known.
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LegibilityCard.WHITE
        typeface = Typeface.DEFAULT
    }

    /** The width in px of [text] at [sizePx], with the font the card is drawn in. */
    fun measure(text: String, sizePx: Int): Int = synchronized(textPaint) {
        textPaint.textSize = sizePx.toFloat()
        ceil(textPaint.measureText(text)).toInt()
    }

    /** The legibility card; its texts end with [sample] (a street and a distance, in the rider's language). */
    fun legibility(sample: String = LegibilityCard.SAMPLE): Bitmap {
        val card = LegibilityCard.layout(::measure, sample)
        val bitmap = frame()
        val canvas = Canvas(bitmap)
        val solid = Paint().apply { isAntiAlias = false }

        solid.color = LegibilityCard.OUTLINE_GREY
        solid.style = Paint.Style.STROKE
        solid.strokeWidth = 1f
        // A ring through the middle of the circle's outermost pixels: its centre is a pixel corner.
        canvas.drawCircle(card.outline.centerX.toFloat(), card.outline.centerY.toFloat(), OUTLINE_RADIUS, solid)
        solid.style = Paint.Style.FILL
        // A line where something covers the picture's bottom; since the glass was measured nothing does.
        if (card.outline.usableBottom < DashGlass.FRAME_H) {
            val dy = (card.outline.usableBottom - card.outline.centerY).toDouble()
            val half = sqrt(card.outline.radius.toDouble() * card.outline.radius - dy * dy).toInt()
            canvas.drawRect(rect(RectPx(card.outline.centerX - half, card.outline.usableBottom, 2 * half, 1)), solid)
        }

        solid.color = LegibilityCard.WHITE
        for (line in card.lines) canvas.drawRect(rect(line.box), solid)
        for (arrow in card.arrows) canvas.drawPath(arrowPath(arrow.box), solid)
        for (patch in card.patches) {
            solid.color = patch.color
            canvas.drawRect(rect(patch.rect), solid)
        }
        synchronized(textPaint) {
            for (label in card.labels) {
                textPaint.textSize = label.sizePx.toFloat()
                canvas.drawText(label.text, label.box.x.toFloat(), label.baseline.toFloat(), textPaint)
            }
        }
        return bitmap
    }

    /** [source] whole on the frame, as large as it fits, centred, black around it. */
    fun picture(source: Bitmap): Bitmap {
        val bitmap = frame()
        val at = PicturePlacement.inFrame(source.width, source.height)
        Canvas(bitmap).drawBitmap(source, null, rect(at), Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    /**
     * The picture at [uri], read at no more than about twice the frame's size (a phone photo is far larger than
     * the dash needs); null if it cannot be read.
     */
    fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= DashGlass.FRAME_W && bounds.outHeight / (sample * 2) >= DashGlass.FRAME_H) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun frame(): Bitmap = createBitmap(DashGlass.FRAME_W, DashGlass.FRAME_H).apply { eraseColor(LegibilityCard.BLACK) }

    private fun rect(r: RectPx) = Rect(r.x, r.y, r.right, r.bottom)

    /** Pointing up: a head over the top half, a shaft a quarter as wide under it. */
    private fun arrowPath(box: RectPx): Path = Path().apply {
        val s = box.w.toFloat()
        val x = box.x.toFloat()
        val y = box.y.toFloat()
        moveTo(x + s / 2, y)
        lineTo(x + s, y + s / 2)
        lineTo(x + s * 5 / 8, y + s / 2)
        lineTo(x + s * 5 / 8, y + s)
        lineTo(x + s * 3 / 8, y + s)
        lineTo(x + s * 3 / 8, y + s / 2)
        lineTo(x, y + s / 2)
        close()
    }
}
