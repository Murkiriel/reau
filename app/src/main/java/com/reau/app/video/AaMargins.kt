// The margin rule ([AaMargins.forAspect]) is adapted from OpenCfMoto (AGPL-3.0):
// app/src/main/java/dev/zanderp/opencfmoto/BikeProfile.kt (AaMargins), commit 0abbe2a.
// Changes: it takes the coded frame's size as two numbers instead of OpenCfMoto's AaVideoSpec; contentRect is new.
package com.reau.app.video

import kotlin.math.roundToInt

/**
 * The margins a head unit advertises to Android Auto with its video configuration.
 *
 * Android Auto only renders a few fixed frame sizes (800×480, 1280×720, …). With margins it draws its screen in a
 * smaller area inside that frame, so the picture already has the shape the panel needs and nothing has to be cut
 * off afterwards. [marginW] and [marginH] are the totals taken off each axis; Android Auto splits each evenly, so
 * the content is CENTRED in the coded frame (OpenCfMoto checked this against Google's desktop head unit:
 * marginwidth 280 gives 140 + 140).
 */
data class AaMargins(val marginW: Int, val marginH: Int) {
    val any: Boolean get() = marginW > 0 || marginH > 0

    /** Where Android Auto's screen sits inside a [codedW]×[codedH] frame. */
    fun contentRect(codedW: Int, codedH: Int): RectPx {
        val w = (codedW - marginW).coerceIn(1, codedW)
        val h = (codedH - marginH).coerceIn(1, codedH)
        return RectPx((codedW - w) / 2, (codedH - h) / 2, w, h)
    }

    companion object {
        val NONE = AaMargins(0, 0)
        private const val MIN_USABLE = 16

        /**
         * Margins that shrink a [codedW]×[codedH] frame so the usable area has the shape [targetW]:[targetH],
         * trimming only the axis that is too long (the other keeps the full coded size, to lose the least detail).
         */
        fun forAspect(codedW: Int, codedH: Int, targetW: Int, targetH: Int): AaMargins {
            if (targetW <= 0 || targetH <= 0) return NONE
            val codedAspect = codedW.toDouble() / codedH
            val targetAspect = targetW.toDouble() / targetH
            return if (codedAspect < targetAspect) {
                // The coded frame is too tall for the target: trim height.
                val usableH = (codedW * targetH.toDouble() / targetW).roundToInt().coerceIn(MIN_USABLE, codedH)
                AaMargins(0, codedH - usableH)
            } else {
                // The coded frame is too wide for the target: trim width.
                val usableW = (codedH * targetW.toDouble() / targetH).roundToInt().coerceIn(MIN_USABLE, codedW)
                AaMargins(codedW - usableW, 0)
            }
        }
    }
}
