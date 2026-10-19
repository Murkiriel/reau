package com.reau.app.video

import android.graphics.Color
import org.junit.Assert.assertTrue

/**
 * Test helper: looks at a decoded picture of what went to the dash and checks it against what [TestPattern] put in
 * and the mapping in force. Moved here unchanged from DashCompositorTest when a second test needed it.
 */
object PictureCheck {
    private const val BORDER_PX = 4
    private const val CELL_BORDER_SOURCE_PX = 20.0

    private val NAMED = mapOf(
        "red" to Color.RED, "green" to Color.GREEN, "blue" to Color.BLUE, "yellow" to Color.YELLOW, "black" to Color.BLACK,
    )

    /** The nearest of the five colours a picture can have here. */
    fun name(color: Int): String = NAMED.minByOrNull { (_, c) ->
        val dr = Color.red(color) - Color.red(c)
        val dg = Color.green(color) - Color.green(c)
        val db = Color.blue(color) - Color.blue(c)
        dr * dr + dg * dg + db * db
    }!!.key

    /** Checks that [picture] is the whole of [TestPattern] at its own size: each of the 48 cells, in its middle. */
    fun assertIsThePattern(picture: H264TestDecoder.Picture) {
        assertTrue("${picture.width}x${picture.height}", picture.width == TestPattern.WIDTH && picture.height == TestPattern.HEIGHT)
        val wrong = mutableListOf<String>()
        for (y in 40 until TestPattern.HEIGHT step 80) {
            for (x in 50 until TestPattern.WIDTH step 100) {
                val expected = name(TestPattern.colorAt(x, y))
                val actual = name(picture.colorAt(x, y))
                if (actual != expected) wrong += "($x,$y) $actual, expected $expected"
            }
        }
        assertTrue("${wrong.size} of 48 cells wrong: $wrong", wrong.isEmpty())
    }

    /**
     * Checks [picture] against [mapping]: inside the destination every sampled pixel must have the colour of the
     * source pixel it comes from; outside it, black. Pixels near a border (of a cell, or of the destination) are
     * skipped: scaling and the codec blur them.
     */
    fun assertShows(picture: H264TestDecoder.Picture, mapping: FrameFit.Mapping) {
        val d = mapping.dest
        val s = mapping.source
        val wrong = mutableListOf<String>()
        var inside = 0
        var outside = 0
        for (py in 2 until DashGlass.FRAME_H step 6) {
            for (px in 2 until DashGlass.FRAME_W step 6) {
                val insideBy = minOf(px - d.x, d.right - 1 - px, py - d.y, d.bottom - 1 - py)
                val expected = when {
                    insideBy >= BORDER_PX -> {
                        val sx = s.x + (px + 0.5 - d.x) * s.w / d.w
                        val sy = s.y + (py + 0.5 - d.y) * s.h / d.h
                        // A source point outside Android Auto's picture must come out black, not the edge
                        val beyond = maxOf(-sx, sx - TestPattern.WIDTH, -sy, sy - TestPattern.HEIGHT)
                        if (beyond > -CELL_BORDER_SOURCE_PX && beyond < CELL_BORDER_SOURCE_PX) continue
                        if (beyond >= CELL_BORDER_SOURCE_PX) {
                            outside++
                            "black"
                        } else {
                            if (TestPattern.distanceToCellEdge(sx, sy) < CELL_BORDER_SOURCE_PX) continue
                            inside++
                            name(TestPattern.colorAt(sx.toInt(), sy.toInt()))
                        }
                    }
                    insideBy <= -BORDER_PX -> {
                        outside++
                        "black"
                    }
                    else -> continue
                }
                val actual = name(picture.colorAt(px, py))
                if (actual != expected) wrong += "($px,$py) $actual, expected $expected"
            }
        }
        assertTrue("too few pixels checked inside the destination: $inside", inside >= 40)
        assertTrue("too few pixels checked that must be black (outside the destination or the picture): $outside", outside >= 40)
        assertTrue("${wrong.size} of ${inside + outside} pixels wrong for $mapping: ${wrong.take(12)}", wrong.isEmpty())
    }
}
