package com.reau.app.video

/** What the compositor does at a tick of its clock. */
enum class TickAction {
    /** A new picture arrived since the last frame: draw it. */
    DRAW,

    /** Nothing new, and the idle interval is up: send the last picture (or black) again. */
    REPEAT,

    /** Nothing new and not time to repeat yet. */
    WAIT,
}

/**
 * The compositor's arithmetic, kept away from OpenGL so it runs in a plain JVM test: what to do at a tick, which
 * part of the source texture to sample, where on the encoder's frame to draw, and the time on each frame.
 */
object CompositorMath {

    /**
     * The tick: a new picture is drawn at once; with nothing new, the last one is repeated at the tick NEAREST
     * to [idleMs] after the previous frame, so the idle rate is the one asked for and not the next tick after it
     * (at 16 ticks a second, 250 ms falls between the 4th tick, 248 ms, and the 5th, 310 ms).
     *
     * [sinceLastFrameMs] is the nominal time since the last frame went out: a whole number of [activeMs] ticks.
     */
    fun decide(newImage: Boolean, sinceLastFrameMs: Long, activeMs: Long, idleMs: Long): TickAction = when {
        newImage -> TickAction.DRAW
        2 * sinceLastFrameMs + activeMs >= 2 * idleMs -> TickAction.REPEAT
        else -> TickAction.WAIT
    }

    /**
     * A rectangle of the source picture in texture coordinates: `u = offsetU + s × scaleU`, `v = offsetV + t ×
     * scaleV`, for s and t running 0..1 across the drawn quad, left to right and BOTTOM to top.
     */
    data class TexWindow(val offsetU: Float, val offsetV: Float, val scaleU: Float, val scaleV: Float)

    /**
     * Where [source] (pixels, rows counted from the top) sits in a [frameW]×[frameH] picture, in texture
     * coordinates. Texture rows are counted from the bottom, hence the flip of the vertical offset.
     */
    fun texWindow(source: RectPx, frameW: Int, frameH: Int): TexWindow = TexWindow(
        offsetU = source.x.toFloat() / frameW,
        offsetV = (frameH - source.bottom).toFloat() / frameH,
        scaleU = source.w.toFloat() / frameW,
        scaleV = source.h.toFloat() / frameH,
    )

    /**
     * Where Android Auto's whole [sourceW]×[sourceH] picture goes on the phone's preview, a [canvasW]×[canvasH]
     * surface: as big as fits, centred, on black. Centred, so the same rectangle in
     * OpenGL coordinates. Nothing with no surface yet.
     */
    fun previewViewport(sourceW: Int, sourceH: Int, canvasW: Int, canvasH: Int): RectPx {
        if (minOf(sourceW, sourceH, canvasW, canvasH) <= 0) return RectPx(0, 0, 0, 0)
        val scale = minOf(canvasW.toDouble() / sourceW, canvasH.toDouble() / sourceH)
        val w = Math.round(sourceW * scale).toInt()
        val h = Math.round(sourceH * scale).toInt()
        return RectPx((canvasW - w) / 2, (canvasH - h) / 2, w, h)
    }

    /** [dest] (rows counted from the top of the dash's frame) as an OpenGL viewport: rows counted from the bottom. */
    fun viewport(dest: RectPx, frameH: Int = DashGlass.FRAME_H): RectPx = RectPx(dest.x, frameH - dest.bottom, dest.w, dest.h)

    /**
     * The time stamped on a frame: the video clock [ptsMs] in whole milliseconds, and always later than
     * [previousStampMs]. The encoder drops a frame whose time is not after the one before.
     */
    fun stamp(previousStampMs: Long, ptsMs: Double): Long = maxOf(previousStampMs + 1, ptsMs.toLong())
}
