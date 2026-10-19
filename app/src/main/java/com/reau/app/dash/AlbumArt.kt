package com.reau.app.dash

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import java.io.ByteArrayOutputStream

/**
 * The album's picture as the dash takes it: the middle of
 * the picture taken as a square of [SIDE] pixels, JPEG at quality [QUALITY]. Android Auto's own picture can be large, so
 * it is read at a reduced size first. [forDash] remembers the last one, so a track Android Auto repeats is not
 * decoded again (it repeats the metadata often).
 */
class AlbumArt {
    private var lastSource: ByteArray? = null
    private var lastJpeg: ByteArray? = null

    /** The JPEG for [source] (null when [source] is null or is not a picture); the same one while [source] is the same bytes. */
    @Synchronized
    fun forDash(source: ByteArray?): ByteArray? {
        if (source == null) {
            lastSource = null
            lastJpeg = null
            return null
        }
        if (lastSource?.contentEquals(source) == true) return lastJpeg
        lastSource = source
        lastJpeg = runCatching { jpeg(source) }.getOrNull()
        return lastJpeg
    }

    private fun jpeg(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(minOf(bounds.outWidth, bounds.outHeight)) }
        return BitmapFactory.decodeByteArray(source, 0, source.size, options)?.let(::squareJpeg)
    }

    /** [decoded] cut to the square the dash gets, as a JPEG; null when the encoder refuses it. */
    private fun squareJpeg(decoded: Bitmap): ByteArray? {
        val square = ThumbnailUtils.extractThumbnail(decoded, SIDE, SIDE)
        val out = ByteArrayOutputStream()
        val ok = square.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (square !== decoded) decoded.recycle()
        square.recycle()
        return if (ok) out.toByteArray() else null
    }

    companion object {
        /** The side of the picture the dash gets. */
        const val SIDE = 75

        /** JPEG quality. */
        const val QUALITY = 50

        /** The power of two that leaves the short side of the picture at least [SIDE] pixels; 1 when it is smaller. */
        fun sampleSize(shortSide: Int): Int {
            var n = 1
            while (shortSide / (n * 2) >= SIDE) n *= 2
            return n
        }
    }
}
