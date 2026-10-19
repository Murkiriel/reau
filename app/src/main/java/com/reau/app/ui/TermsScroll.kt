package com.reau.app.ui

import kotlin.math.roundToLong

/**
 * The pace at which the "Descer até o fim" button carries the terms to their end: a steady scroll, slow enough for the
 * eye to see the text go by (as if it were being read), not a jump.
 */
object TermsScroll {
    /** The reading pace in dp per second: about 5,000 px/s on a 1080 px wide phone (density 2.75), the same on any density. */
    const val DP_PER_SECOND = 1_800f

    /** Never quicker than this, however short the distance: the rider must see the text roll. */
    const val MIN_MS = 1_000

    /** Never slower than this, however long the text: the rider is not kept waiting. */
    const val MAX_MS = 1_000

    private const val MS_PER_SECOND = 1_000L

    /** How long to scroll [distancePx] at [DP_PER_SECOND], on a screen of [density] px per dp, kept between [MIN_MS] and [MAX_MS]. */
    fun durationMs(distancePx: Int, density: Float): Int {
        if (distancePx <= 0 || density <= 0f) return MIN_MS
        val ms = (distancePx.toDouble() / (DP_PER_SECOND * density) * MS_PER_SECOND).roundToLong()
        return ms.coerceIn(MIN_MS.toLong(), MAX_MS.toLong()).toInt()
    }
}
