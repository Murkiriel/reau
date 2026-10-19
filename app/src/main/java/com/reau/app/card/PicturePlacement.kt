package com.reau.app.card

import com.reau.app.video.DashGlass
import com.reau.app.video.RectPx
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a picture chosen by the rider goes on the dash's 526×300 frame: whole, unstretched, as large as the frame
 * allows, centred, black around it. A picture made for the dash (526×300, like the bench cards) fills it.
 */
object PicturePlacement {
    fun inFrame(width: Int, height: Int): RectPx {
        require(width > 0 && height > 0) { "an empty picture: ${width}x$height" }
        val scale = min(DashGlass.FRAME_W.toDouble() / width, DashGlass.FRAME_H.toDouble() / height)
        val w = (width * scale).roundToInt().coerceIn(1, DashGlass.FRAME_W)
        val h = (height * scale).roundToInt().coerceIn(1, DashGlass.FRAME_H)
        return RectPx((DashGlass.FRAME_W - w) / 2, (DashGlass.FRAME_H - h) / 2, w, h)
    }
}
