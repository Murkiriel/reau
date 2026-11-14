package com.reau.app.ui

import kotlin.math.roundToLong

/**
 * The pace at which the "Descer até o fim" button carries the terms to their end: a steady scroll, slow enough for the
 * eye to see the text go by (as if it were being read), not a jump.
 */
object TermsScroll {
    /**
     * The pace in dp per second, the same on any density: about a screen and a half a second, a text the eye can follow as it goes
     * by. (It was 1,800, three times quicker, but a 1 s ceiling made it a jump that nobody could see.)
     */
    const val DP_PER_SECOND = 600f

    /** Never quicker than this, however short the distance: the rider must see the text roll. */
    const val MIN_MS = 1_000

    /** Never slower than this, however long the text: the rider is not kept waiting (a touch on the text stops the roll at any time). */
    const val MAX_MS = 10_000

    private const val MS_PER_SECOND = 1_000L

    /** How long to scroll [distancePx] at [DP_PER_SECOND], on a screen of [density] px per dp, kept between [MIN_MS] and [MAX_MS]. */
    fun durationMs(distancePx: Int, density: Float): Int {
        if (distancePx <= 0 || density <= 0f) return MIN_MS
        val ms = (distancePx.toDouble() / (DP_PER_SECOND * density) * MS_PER_SECOND).roundToLong()
        return ms.coerceIn(MIN_MS.toLong(), MAX_MS.toLong()).toInt()
    }
}
