package com.reau.app.aa

import com.reau.app.video.RectPx

/**
 * A finger on the phone's "Tela do Android Auto" (to use Android Auto from the phone, behind
 * the switch "Tocar no Android Auto pela tela do celular", off). [x] and [y] are the screen's own pixels; the screen
 * shows Android Auto's whole picture in a viewport (CompositorMath.previewViewport), and [toVideo] scales the finger
 * back into the video's pixels, which is what the announced touch screen measures. One finger: taps and drags.
 */
data class PhoneTouch(val action: Action, val x: Float, val y: Float) {
    enum class Action { DOWN, MOVE, UP, CANCEL }

    /** What goes to Android Auto: an action at a point of the video. */
    data class Sent(val action: Action, val x: Int, val y: Int)

    /**
     * One finger's stroke: a stroke that went down off the picture (the black bands) is dropped whole, so Android
     * Auto never gets a move or a lift without the touch that started it; one that went down on it goes on to the
     * lift, clamped to the picture's edge when the finger leaves it.
     */
    class Stroke {
        private var down = false
        private var last: Pair<Int, Int>? = null

        fun next(touch: PhoneTouch, viewport: RectPx, videoW: Int, videoH: Int): Sent? {
            if (touch.action == Action.DOWN) down = false
            val at = if (touch.action == Action.DOWN || down) toVideo(touch, viewport, videoW, videoH) else null
            down = at != null && touch.action != Action.UP && touch.action != Action.CANCEL
            if (at != null) last = at
            return at?.let { Sent(touch.action, it.first, it.second) }
        }

        /** A finger that vanished (the browser went away mid-stroke): the CANCEL to send at its last point, once; null if none is down. */
        fun cancel(): Sent? {
            val at = last?.takeIf { down } ?: return null
            down = false
            return Sent(Action.CANCEL, at.first, at.second)
        }
    }

    companion object {
        /** The video pixel under the finger; null for a touch that starts off the picture, or with no picture. */
        fun toVideo(touch: PhoneTouch, viewport: RectPx, videoW: Int, videoH: Int): Pair<Int, Int>? {
            val (action, x, y) = touch
            val insideX = x >= viewport.x && x < viewport.right
            val insideY = y >= viewport.y && y < viewport.bottom
            val usable = minOf(viewport.w, viewport.h, videoW, videoH) > 0 && (action != Action.DOWN || (insideX && insideY))
            return if (!usable) {
                null
            } else {
                val vx = ((x - viewport.x) * videoW / viewport.w).toInt().coerceIn(0, videoW - 1)
                val vy = ((y - viewport.y) * videoH / viewport.h).toInt().coerceIn(0, videoH - 1)
                vx to vy
            }
        }
    }
}
