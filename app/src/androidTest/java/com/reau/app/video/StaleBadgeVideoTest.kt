package com.reau.app.video

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The "picture stuck" badge is in the video that goes to the dash: amber letters on a dark pill at the bottom centre,
 * and gone once taken off. The compositor runs with no Android Auto picture (black frames, so the only amber is the
 * badge), feeds the real dash encoder, and what comes out is decoded again and looked at.
 */
@RunWith(AndroidJUnit4::class)
class StaleBadgeVideoTest {
    private class Out(val data: ByteArray, val ptsMs: Long)

    private val log = CopyOnWriteArrayList<String>()
    private val out = CopyOnWriteArrayList<Out>()
    private var compositor: DashCompositor? = null
    private var encoder: DashEncoder? = null

    @After
    fun tearDown() {
        compositor?.release()
        encoder?.release()
    }

    private fun run(whileRunning: (DashCompositor) -> Unit): H264TestDecoder.Picture {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        c.setPace(DashCompositor.Pace(62, 250))
        val enc = DashEncoder { data, _, ptsMs -> out += Out(data, ptsMs) }.also { encoder = it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        c.setOutput(checkNotNull(enc.inputSurface)) { pts -> enc.frameSubmitted(pts) }
        Thread.sleep(SETTLE_MS)
        whileRunning(c)
        Thread.sleep(SETTLE_MS)
        c.clearOutput()
        enc.release()
        val pictures = H264TestDecoder().decode(out.map { H264TestDecoder.Chunk(it.data, it.ptsMs) })
        assertTrue("nothing was decoded from ${out.size} buffers; $log", pictures.isNotEmpty())
        return pictures.last()
    }

    private fun isAmber(color: Int) = Color.red(color) > 170 && Color.green(color) in 90..210 && Color.blue(color) < 90

    private fun ambers(picture: H264TestDecoder.Picture, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(picture.height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(picture.width)) if (isAmber(picture.colorAt(x, y))) n++
        }
        return n
    }

    private fun box(text: String): IntArray {
        val badge = StaleBadgeInk.render(text)
        val (x, y) = StaleBadgeInk.corner(badge.width, badge.height)
        return intArrayOf(x, y, x + badge.width, y + badge.height).also { badge.recycle() }
    }

    @Test
    fun theBadgeIsAmberInsideItsBoxAtTheBottomCentreAndNowhereElse() {
        val picture = run { it.setStaleBadge(TEXT) }
        val (x0, y0, x1, y1) = box(TEXT)
        val inside = ambers(picture, x0, y0, x1, y1)
        val outside = ambers(picture, 0, 0, picture.width, picture.height) - inside
        assertTrue("amber pixels in the box: $inside", inside > 120)
        assertTrue("amber pixels outside the box: $outside", outside <= STRAY)
        assertTrue("centred", kotlin.math.abs((x0 + x1) / 2 - picture.width / 2) <= 2)
        assertTrue("at the bottom", y1 > picture.height - 20)
    }

    @Test
    fun takenOffWhileRunningItGoes() {
        val picture = run { c ->
            c.setStaleBadge(TEXT)
            Thread.sleep(SETTLE_MS)
            c.setStaleBadge(null)
        }
        assertTrue("amber left: ${ambers(picture, 0, 0, picture.width, picture.height)}", ambers(picture, 0, 0, picture.width, picture.height) <= STRAY)
    }

    @Test
    fun withoutTheBadgeNothingIsAmber() {
        val picture = run { }
        assertTrue(ambers(picture, 0, 0, picture.width, picture.height) <= STRAY)
    }

    private companion object {
        const val TEXT = "PICTURE STUCK"
        const val SETTLE_MS = 1_200L
        const val STRAY = 8
    }
}
