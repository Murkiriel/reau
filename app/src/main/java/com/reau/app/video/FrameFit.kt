package com.reau.app.video

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which part of Android Auto's frame goes where on the dash's frame.
 *
 * Android Auto draws at least 800×480; the rider sees the dash's 526×300 frame less its two top corners, which
 * the round glass cuts ([DashGlass], measured on a real dash). There are three fits to choose from,
 * and the choice is a setting.
 *
 * Every function takes the CONTENT rectangle of the source: the whole coded frame, or the area Android Auto was
 * told to draw in ([AaMargins.contentRect]). Nothing here stretches: source and destination keep one shape.
 */
object FrameFit {
    /** How far the crop's window may slide, in % of the slack each way (past both edges). */
    const val CENTER_MIN = -100
    const val CENTER_MAX = 200


    /** [source] in the pixels of Android Auto's frame, [dest] in the pixels of the dash's 526×300 frame. */
    /** [pastEdge]: the crop's window was slid past the picture's edge on purpose; only then may [source] leave
     *  Android Auto's frame, and the outside goes black. */
    data class Mapping(val source: RectPx, val dest: RectPx, val pastEdge: Boolean = false) {
        /** Dash pixels per source pixel: 0.375 means text drawn 24 px tall arrives 9 px tall. */
        val scale: Double get() = if (source.w > 0) dest.w.toDouble() / source.w else 0.0
    }

    /**
     * The whole content, as large as it can be with all four corners on the glass. It rests on the frame's bottom
     * edge, where the circle is nearly at its widest. For 800×480 that is 370×222 at (78, 78): a scale of 0.4625.
     */
    fun wholeVisible(content: RectPx): Mapping {
        val aspect = content.w.toDouble() / content.h
        val r2 = DashGlass.RADIUS.toDouble() * DashGlass.RADIUS
        val below = (DashGlass.USABLE_BOTTOM - DashGlass.CENTER_Y).toDouble()
        // Even widths only, so the rectangle is centred on the glass to the pixel.
        var w = DashGlass.DOME_BOX.w
        while (w > 2) {
            val half = w / 2.0
            val above = w / aspect - below // how far above the circle's centre the top edge is
            val fits = w / aspect <= DashGlass.USABLE_BOTTOM
            if (fits && half * half + below * below <= r2 && half * half + above * above <= r2) break
            w -= 2
        }
        val h = (w / aspect).roundToInt().coerceAtLeast(1)
        return Mapping(content, RectPx(DashGlass.CENTER_X - w / 2, DashGlass.USABLE_BOTTOM - h, w, h))
    }

    /**
     * The whole content, as large as fits the dash's frame, resting on its bottom edge. The circle cuts its top
     * corners. For 800×480 that is 500×300 at (13, 0): a scale of 0.625, 84% of it on the glass.
     */
    fun fillDome(content: RectPx): Mapping {
        val box = DashGlass.DOME_BOX
        val scale = min(box.w.toDouble() / content.w, box.h.toDouble() / content.h)
        val w = (content.w * scale).roundToInt().coerceIn(1, box.w)
        val h = (content.h * scale).roundToInt().coerceIn(1, box.h)
        return Mapping(content, RectPx(DashGlass.CENTER_X - w / 2, box.bottom - h, w, h))
    }

    /**
     * A window of the frame's own shape (526:300) cut out of the content and shown over the whole frame.
     * [scalePercent] is how large the source appears: 80 shows a 658×375 window at 0.8 dash pixels per source
     * pixel; 100 is one to one. Below what the content allows (about 66% for 800×480), the window stops at the
     * largest one that fits. [centerXPercent] and [centerYPercent] slide the window over the content: 0 and 100 are
     * its edges, 50 the middle; from -100 to 200 the window goes past the edge by up
     * to the whole slack, and what falls outside Android Auto's picture reaches the dash black (CompositorGl).
     */
    fun centerCrop(content: RectPx, scalePercent: Int, centerXPercent: Int = 50, centerYPercent: Int = 50): Mapping {
        val box = DashGlass.DOME_BOX
        val (w, h) = cropSize(content, scalePercent)
        val x = content.x + (content.w - w) * centerXPercent.coerceIn(CENTER_MIN, CENTER_MAX) / 100
        val y = content.y + (content.h - h) * centerYPercent.coerceIn(CENTER_MIN, CENTER_MAX) / 100
        val pastEdge = x < content.x || y < content.y || x + w > content.right || y + h > content.bottom
        return Mapping(RectPx(x, y, w, h), box, pastEdge)
    }

    /** The crop's window at [scalePercent]: the frame's shape, at most the content (see [centerCrop]). */
    fun cropSize(content: RectPx, scalePercent: Int): Pair<Int, Int> {
        val box = DashGlass.DOME_BOX
        val scale = scalePercent.coerceIn(1, 100) / 100.0
        var w = (box.w / scale).roundToInt()
        var h = (box.h / scale).roundToInt()
        if (w > content.w || h > content.h) {
            val largest = min(content.w.toDouble() / box.w, content.h.toDouble() / box.h)
            w = (box.w * largest).roundToInt().coerceAtMost(content.w)
            h = (box.h * largest).roundToInt().coerceAtMost(content.h)
        }
        return w to h
    }

    /**
     * The crop's window placed by its top left pixel inside the
     * content, [x] across and [y] down (null: centred). It may leave the picture by all of it but a pixel ([cropRange]);
     * what falls outside goes black on the dash.
     */
    fun cropAt(content: RectPx, scalePercent: Int, x: Int?, y: Int?): Mapping {
        val (w, h) = cropSize(content, scalePercent)
        val (rangeX, rangeY) = cropRange(content, scalePercent)
        val left = content.x + (x ?: ((content.w - w) / 2)).coerceIn(rangeX)
        val top = content.y + (y ?: ((content.h - h) / 2)).coerceIn(rangeY)
        val pastEdge = left < content.x || top < content.y || left + w > content.right || top + h > content.bottom
        return Mapping(RectPx(left, top, w, h), DashGlass.DOME_BOX, pastEdge)
    }

    /**
     * Where the window's left and top edges may go (inside the content): the whole window past
     * either edge but one pixel, which stays on the picture (all of it outside is a black dash, refused by [problems]).
     */
    fun cropRange(content: RectPx, scalePercent: Int): Pair<IntRange, IntRange> {
        val (w, h) = cropSize(content, scalePercent)
        return -(w - 1)..content.w - 1 to -(h - 1)..content.h - 1
    }

    /**
     * [mapping] with its picture moved [dx] dash pixels to the right and [dy] down (negative: left and up), to
     * correct a picture that sits too high or off to one side. The picture stops at
     * the edges of the dash's frame, so a move never makes the mapping invalid; only the destination moves.
     */
    fun moved(mapping: Mapping, dx: Int, dy: Int): Mapping {
        val d = mapping.dest
        val x = (d.x + dx).coerceIn(0, (DashGlass.FRAME_W - d.w).coerceAtLeast(0))
        val y = (d.y + dy).coerceIn(0, (DashGlass.FRAME_H - d.h).coerceAtLeast(0))
        return mapping.copy(dest = d.copy(x = x, y = y))
    }

    /** What is wrong with a mapping, in this order; empty when it can go to the compositor. */
    enum class Problem {
        /** The source rectangle is not inside Android Auto's frame; for a crop slid past the edge ([Mapping.pastEdge]),
         *  no part of it is. */
        SOURCE_OUTSIDE_FRAME,

        /** The destination rectangle is not inside the dash's 526×300 frame. */
        DEST_OUTSIDE_FRAME,

        /** A side of either rectangle is under [MIN_SIDE] px. */
        TOO_SMALL,

        /** Source and destination differ in shape by more than [MAX_DISTORTION]: the picture would be stretched. */
        DISTORTED,

        /** No pixel of the destination is on the glass. */
        NOT_VISIBLE,
    }

    const val MIN_SIDE = 16

    /** Whole-pixel rounding moves a shape by a fraction of a percent; a real stretch is far above this. */
    const val MAX_DISTORTION = 0.02

    /** Checks a mapping (one typed into the settings, say) against a [frameW]×[frameH] Android Auto frame. */
    fun problems(mapping: Mapping, frameW: Int, frameH: Int): List<Problem> = buildList {
        val (s, d) = mapping
        val sourceOk = if (mapping.pastEdge) s.touches(frameW, frameH) else s.isInside(frameW, frameH)
        if (!sourceOk) add(Problem.SOURCE_OUTSIDE_FRAME)
        if (!d.isInside(DashGlass.FRAME_W, DashGlass.FRAME_H)) add(Problem.DEST_OUTSIDE_FRAME)
        val tooSmall = minOf(s.w, s.h, d.w, d.h) < MIN_SIDE
        if (tooSmall) add(Problem.TOO_SMALL)
        if (!tooSmall && distortion(s, d) > MAX_DISTORTION) add(Problem.DISTORTED)
        if (DashGlass.visibleFraction(d) <= 0.0) add(Problem.NOT_VISIBLE)
    }

    /** How far apart the two shapes are, as a fraction of the source's aspect ratio. */
    private fun distortion(s: RectPx, d: RectPx): Double {
        val sourceAspect = s.w.toDouble() / s.h
        return abs(d.w.toDouble() / d.h - sourceAspect) / sourceAspect
    }
}

private fun RectPx.touches(frameW: Int, frameH: Int): Boolean =
    w > 0 && h > 0 && x < frameW && y < frameH && right > 0 && bottom > 0

private fun RectPx.isInside(frameW: Int, frameH: Int): Boolean =
    x >= 0 && y >= 0 && w > 0 && h > 0 && right <= frameW && bottom <= frameH
