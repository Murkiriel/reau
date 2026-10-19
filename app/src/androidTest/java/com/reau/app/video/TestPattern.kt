package com.reau.app.video

import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Surface

/**
 * Test helper: a synthetic "Android Auto" picture. An 8×6 grid of 100×80 cells in four colours, laid out without
 * any symmetry, so a picture that is shifted, flipped, scaled or cropped wrongly shows the wrong colours almost
 * everywhere. Drawn on the compositor's input surface with a software Canvas.
 */
object TestPattern {
    const val WIDTH = 800
    const val HEIGHT = 480
    private const val CELL_W = 100
    private const val CELL_H = 80

    val COLORS = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    private val CELLS = listOf(
        "01231302",
        "23102031",
        "10323120",
        "32012103",
        "03121320",
        "21302013",
    )

    /** The colour of the pattern at source pixel ([x], [y]). */
    fun colorAt(x: Int, y: Int): Int = COLORS[CELLS[y / CELL_H][x / CELL_W] - '0']

    /** How far ([x], [y]) is from the nearest cell border, in source pixels. */
    fun distanceToCellEdge(x: Double, y: Double): Double {
        val dx = x % CELL_W
        val dy = y % CELL_H
        return minOf(dx, CELL_W - dx, dy, CELL_H - dy)
    }

    /**
     * Starts [compositor] for the pattern's size; true once it is up. [DashCompositor.start] waits 2 s for its GL
     * thread and answers false after that, but the thread carries on and logs "compositor ready" when it gets there:
     * a busy emulator (right after an install, or tired) was seen taking longer, so a false is waited out here, up to
     * [waitMs], for that line. A start that really failed logs "compositor init failed" and stays false.
     * [log] is the list the compositor's own log lines are collected in.
     */
    fun startCompositor(compositor: DashCompositor, log: List<String>, waitMs: Long = 15_000): Boolean {
        if (compositor.start(WIDTH, HEIGHT)) return true
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (log.none { "compositor ready" in it || "compositor init failed" in it } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        return log.any { "compositor ready" in it }
    }

    /** Draws the pattern on [surface] (800×480) and posts it. [tint] replaces every colour: a way to post a
     *  different picture. An encoder's input surface only takes a [hardware] canvas. */
    fun drawOn(surface: Surface, tint: Int? = null, hardware: Boolean = false) {
        val canvas = if (hardware) surface.lockHardwareCanvas() else surface.lockCanvas(null)
        try {
            val paint = Paint()
            for (row in CELLS.indices) {
                for (col in CELLS[row].indices) {
                    paint.color = tint ?: COLORS[CELLS[row][col] - '0']
                    canvas.drawRect(
                        (col * CELL_W).toFloat(), (row * CELL_H).toFloat(),
                        ((col + 1) * CELL_W).toFloat(), ((row + 1) * CELL_H).toFloat(), paint,
                    )
                }
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
