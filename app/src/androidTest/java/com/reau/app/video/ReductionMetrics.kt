package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Test helper for the reduction bench: the high-quality reference (Lanczos-3, in linear steps on the JVM) and
 * the numbers that compare a reduction with it. Everything on the luma (BT.601) of the destination rectangle, 0..255.
 */
object ReductionMetrics {
    /** Planar float picture, [w]×[h], three channels 0..255. */
    class Rgb(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
    }

    fun toRgb(bitmap: Bitmap): Rgb {
        val out = Rgb(bitmap.width, bitmap.height)
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in px.indices) {
            out.r[i] = Color.red(px[i]).toFloat()
            out.g[i] = Color.green(px[i]).toFloat()
            out.b[i] = Color.blue(px[i]).toFloat()
        }
        return out
    }

    fun luma(p: Rgb): FloatArray = FloatArray(p.w * p.h) { 0.299f * p.r[it] + 0.587f * p.g[it] + 0.114f * p.b[it] }

    private fun lanczos(x: Double, a: Int): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= a) return 0.0
        val px = PI * x
        return a * sin(px) * sin(px / a) / (px * px)
    }

    /**
     * The window [sx], [sy], [sw]×[sh] of [src] (source pixels, may be fractional) shrunk to [dw]×[dh] with a
     * Lanczos-3 filter whose width follows the shrink ratio, so it averages as it should and does not alias.
     */
    fun lanczosReduce(src: Rgb, sx: Double, sy: Double, sw: Double, sh: Double, dw: Int, dh: Int): Rgb {
        val scaleX = dw / sw
        val scaleY = dh / sh
        val fx = min(1.0, scaleX)
        val fy = min(1.0, scaleY)
        val radiusX = 3 / fx
        val radiusY = 3 / fy
        // horizontal pass over every source row that any destination row needs
        val rowsNeeded = (floor(sy - radiusY).toInt().coerceAtLeast(0))..(ceil(sy + sh + radiusY).toInt().coerceAtMost(src.h - 1))
        val mid = Rgb(dw, src.h)
        for (dx in 0 until dw) {
            val centre = sx + (dx + 0.5) / scaleX
            val x0 = floor(centre - radiusX).toInt().coerceAtLeast(0)
            val x1 = ceil(centre + radiusX).toInt().coerceAtMost(src.w - 1)
            val weights = DoubleArray(x1 - x0 + 1) { lanczos((x0 + it + 0.5 - centre) * fx, 3) }
            val total = weights.sum()
            for (y in rowsNeeded) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (i in weights.indices) {
                    val idx = y * src.w + x0 + i
                    r += weights[i] * src.r[idx]
                    g += weights[i] * src.g[idx]
                    b += weights[i] * src.b[idx]
                }
                mid.r[y * dw + dx] = (r / total).toFloat()
                mid.g[y * dw + dx] = (g / total).toFloat()
                mid.b[y * dw + dx] = (b / total).toFloat()
            }
        }
        val out = Rgb(dw, dh)
        for (dy in 0 until dh) {
            val centre = sy + (dy + 0.5) / scaleY
            val y0 = floor(centre - radiusY).toInt().coerceAtLeast(0)
            val y1 = ceil(centre + radiusY).toInt().coerceAtMost(src.h - 1)
            val weights = DoubleArray(y1 - y0 + 1) { lanczos((y0 + it + 0.5 - centre) * fy, 3) }
            val total = weights.sum()
            for (dx in 0 until dw) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (i in weights.indices) {
                    val idx = (y0 + i) * dw + dx
                    r += weights[i] * mid.r[idx]
                    g += weights[i] * mid.g[idx]
                    b += weights[i] * mid.b[idx]
                }
                out.r[dy * dw + dx] = (r / total).toFloat().coerceIn(0f, 255f)
                out.g[dy * dw + dx] = (g / total).toFloat().coerceIn(0f, 255f)
                out.b[dy * dw + dx] = (b / total).toFloat().coerceIn(0f, 255f)
            }
        }
        return out
    }

    /** Result of comparing a picture with the reference over a rectangle. */
    data class Score(val psnr: Double, val ssim: Double, val sharpness: Double)

    /**
     * [test] against [ref], both [w] pixels wide, rows [y0] until [y1] and columns [x0] until [x1]. Sharpness is the
     * mean gradient of [test] over the reference's: 1.0 is as crisp as the reference, below it is softer.
     */
    fun compare(test: FloatArray, ref: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Score {
        var se = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val d = (test[y * w + x] - ref[y * w + x]).toDouble()
                se += d * d
                n++
            }
        }
        val mse = se / max(1, n)
        val psnr = if (mse < 1e-9) 99.0 else 10 * log10(255.0 * 255.0 / mse)
        return Score(psnr, ssim(test, ref, w, x0, y0, x1, y1), gradient(test, w, x0, y0, x1, y1) / max(1e-9, gradient(ref, w, x0, y0, x1, y1)))
    }

    private fun gradient(p: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1 - 1) {
            for (x in x0 until x1 - 1) {
                val gx = p[y * w + x + 1] - p[y * w + x]
                val gy = p[(y + 1) * w + x] - p[y * w + x]
                sum += sqrt((gx * gx + gy * gy).toDouble())
                n++
            }
        }
        return sum / max(1, n)
    }

    /** Mean structural similarity over 8×8 blocks every 4 pixels (uniform window, the usual constants). */
    private fun ssim(a: FloatArray, b: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val c1 = (0.01 * 255) * (0.01 * 255)
        val c2 = (0.03 * 255) * (0.03 * 255)
        var total = 0.0
        var count = 0
        var y = y0
        while (y + BLOCK <= y1) {
            var x = x0
            while (x + BLOCK <= x1) {
                var ma = 0.0
                var mb = 0.0
                for (j in 0 until BLOCK) for (i in 0 until BLOCK) {
                    ma += a[(y + j) * w + x + i]
                    mb += b[(y + j) * w + x + i]
                }
                ma /= BLOCK * BLOCK
                mb /= BLOCK * BLOCK
                var va = 0.0
                var vb = 0.0
                var cov = 0.0
                for (j in 0 until BLOCK) for (i in 0 until BLOCK) {
                    val da = a[(y + j) * w + x + i] - ma
                    val db = b[(y + j) * w + x + i] - mb
                    va += da * da
                    vb += db * db
                    cov += da * db
                }
                val k = BLOCK * BLOCK - 1.0
                va /= k
                vb /= k
                cov /= k
                total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
                count++
                x += STRIDE
            }
            y += STRIDE
        }
        return total / max(1, count)
    }

    private const val BLOCK = 8
    private const val STRIDE = 4
}
