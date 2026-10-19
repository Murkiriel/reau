package com.reau.app.video

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A rectangle in whole pixels: [x], [y] is its top-left corner; [right] and [bottom] are the edges past the last pixel. */
data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
}

/**
 * What of the dash's 526×300 video frame the rider actually sees.
 *
 * Measured on a real dash with a numbered grid sent as a card (REau's card mode): the glass is a round screen
 * 526 px across, centred at ([CENTER_X], [CENTER_Y]) with radius [RADIUS], and the video is its top 300 rows. The
 * bike's own band (trip computer, speed, fuel) is drawn BELOW the video, not over it: the frame's bottom edge shows
 * from side to side. So the whole frame is seen except its two top corners, which the circle cuts.
 *
 * A reading of a photograph gives a much smaller area instead (a circle of radius 150 at (263, 150) with its
 * bottom 38% under the trip computer, cut at y = 186): about a third of what really shows.
 */
object DashGlass {
    const val FRAME_W = 526
    const val FRAME_H = 300
    const val CENTER_X = 263
    const val CENTER_Y = 263
    const val RADIUS = 263

    /** The edge past the last row of the picture area: all of the frame, the bike's band is under it. */
    const val USABLE_BOTTOM = FRAME_H

    /** The smallest rectangle that holds what shows: the whole frame (the circle reaches both sides at y = 263). */
    val DOME_BOX = RectPx(0, 0, FRAME_W, USABLE_BOTTOM)

    /** Whether the rider sees the point ([x], [y]) of the frame. */
    fun isVisible(x: Double, y: Double): Boolean {
        val dx = x - CENTER_X
        val dy = y - CENTER_Y
        return y >= 0.0 && y <= USABLE_BOTTOM && x >= 0.0 && x <= FRAME_W && dx * dx + dy * dy <= RADIUS.toDouble() * RADIUS
    }

    /** How many pixels of row [y] show: the circle's chord there, at most the frame's width; 0 outside the frame. */
    fun visibleWidthAt(y: Int): Int {
        if (y < 0 || y >= USABLE_BOTTOM) return 0
        val dy = (y - CENTER_Y).toDouble()
        return (2 * sqrt(RADIUS.toDouble() * RADIUS - dy * dy)).roundToInt().coerceAtMost(FRAME_W)
    }

    /** The share of [rect]'s pixels the rider sees (by pixel centre); 0.0 for an empty rectangle. */
    fun visibleFraction(rect: RectPx): Double {
        if (rect.w <= 0 || rect.h <= 0) return 0.0
        var visible = 0L
        for (py in rect.y until rect.bottom) {
            for (px in rect.x until rect.right) {
                if (isVisible(px + 0.5, py + 0.5)) visible++
            }
        }
        return visible.toDouble() / (rect.w.toLong() * rect.h)
    }
}
