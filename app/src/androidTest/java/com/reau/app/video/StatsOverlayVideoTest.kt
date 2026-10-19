package com.reau.app.video

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the stats text writes is in the video that goes to the dash. The compositor
 * runs with no Android Auto picture (the frames are black, so the only red is the text), feeds the real dash encoder,
 * and what comes out is decoded again and looked at: red letters where the box is, nothing red elsewhere, a second
 * line (the label) under the numbers, and nothing at all with the text off.
 */
@RunWith(AndroidJUnit4::class)
class StatsOverlayVideoTest {
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

    private fun run(overlay: StatsOverlay, text: StatsText, whileRunning: (DashCompositor) -> Unit = {}): H264TestDecoder.Picture {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        c.setPace(DashCompositor.Pace(62, 250))
        c.setOverlayText(text)
        c.setOverlay(overlay)
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

    private fun isRed(color: Int) = Color.red(color) > 150 && Color.green(color) < 90 && Color.blue(color) < 90

    private fun reds(picture: H264TestDecoder.Picture, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(picture.height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(picture.width)) if (isRed(picture.colorAt(x, y))) n++
        }
        return n
    }

    @Test
    fun theTextIsRedInsideItsBoxAndNowhereElse() {
        val overlay = StatsOverlay(true, 100, 120, 30)
        val text = StatsText("3.8 fps  187 kbps", "painel")
        val (w, h) = StatsOverlayInk.measure(text, overlay.size)
        val picture = run(overlay, text)
        val inside = reds(picture, overlay.x, overlay.y, overlay.x + w, overlay.y + h)
        val outside = reds(picture, 0, 0, picture.width, picture.height) - inside
        assertTrue("red pixels in the box: $inside", inside > 150)
        assertTrue("red pixels outside the box: $outside", outside <= STRAY)
    }

    @Test
    fun theLabelIsASecondLineUnderTheNumbers() {
        val overlay = StatsOverlay(true, 100, 120, 30)
        val text = StatsText("3.8 fps  187 kbps", "painel")
        val (w, h) = StatsOverlayInk.measure(text, overlay.size)
        val picture = run(overlay, text)
        val numbersRows = reds(picture, overlay.x, overlay.y, overlay.x + w, overlay.y + h * 6 / 10)
        val labelRows = reds(picture, overlay.x, overlay.y + h * 7 / 10, overlay.x + w, overlay.y + h)
        assertTrue("numbers: $numbersRows", numbersRows > 100)
        assertTrue("the label under them: $labelRows", labelRows > 20)
        assertTrue("the label is smaller than the numbers", labelRows < numbersRows)
    }

    @Test
    fun theModeAndKeyframeFollowTheLabelAndTheFrameSizeAndSpeedAreAThirdLine() {
        val overlay = StatsOverlay(true, 40, 100, 30)
        val short = StatsText("4.0 fps  200 kbps", "painel")
        val full = StatsText("4.0 fps  200 kbps", "painel", "VBR  QC auto 2s", "6.3 KB/quadro  42 km/h")
        val (_, shortH) = StatsOverlayInk.measure(short, overlay.size)
        val (w, h) = StatsOverlayInk.measure(full, overlay.size)
        assertTrue("the third line makes the box taller", h > shortH + overlay.size / 2)
        val picture = run(overlay, full)
        val thirdLine = reds(picture, overlay.x, overlay.y + shortH, overlay.x + w, overlay.y + h)
        assertTrue("red pixels in the third line: $thirdLine", thirdLine > 60)
        val labelLineRight = reds(picture, overlay.x + w * 6 / 10, overlay.y + h * 4 / 10, overlay.x + w, overlay.y + h * 6 / 10)
        assertTrue("the mode and keyframe sit after the label, to its right: $labelLineRight", labelLineRight > 20)
    }

    @Test
    fun theBorderIsBlackAndTheFrameElsewhereStaysBlack() {
        val overlay = StatsOverlay(true, 100, 120, 30)
        val picture = run(overlay, StatsText(StatsOverlay.UNKNOWN, StatsOverlay.APP))
        val corner = picture.colorAt(10, 10)
        assertTrue("the frame away from the text is black: ${Integer.toHexString(corner)}", Color.red(corner) < 40 && Color.green(corner) < 40)
    }

    @Test
    fun switchedOffNothingIsWritten() {
        val picture = run(StatsOverlay(false, 100, 120, 30), StatsText("3.8 fps  187 kbps", "painel"))
        assertEquals(0, reds(picture, 0, 0, picture.width, picture.height))
    }

    @Test
    fun switchedOnWhileRunningTheTextAppearsAndSwitchedOffItGoes() {
        val overlay = StatsOverlay(true, 100, 120, 20)
        val text = StatsText("3.8 fps  187 kbps", "painel")
        val (w, h) = StatsOverlayInk.measure(text, overlay.size)
        val picture = run(StatsOverlay(false), text) { c ->
            c.setOverlay(overlay)
            Thread.sleep(SETTLE_MS)
            c.setOverlay(overlay.copy(x = 300))
        }
        // moved to x = 300 while running: the old place is empty, the new one has the text
        assertTrue("the old place", reds(picture, overlay.x, overlay.y, overlay.x + w, overlay.y + h) <= STRAY)
        assertTrue("the new place", reds(picture, 300, overlay.y, 300 + w, overlay.y + h) > 150)
    }

    @Test
    fun theNumbersChangeInTheVideoWhenTheTextChanges() {
        val overlay = StatsOverlay(true, 100, 120, 30)
        val wide = StatsText("30.0 fps  1024 kbps", "painel")
        val narrow = StatsText("4.0 fps  98 kbps", "painel")
        val (wideW, h) = StatsOverlayInk.measure(wide, overlay.size)
        val (narrowW, _) = StatsOverlayInk.measure(narrow, overlay.size)
        assertTrue("the test needs different widths", wideW > narrowW + 20)
        val picture = run(overlay, wide) { it.setOverlayText(narrow) }
        // the wider text's right end is empty once the narrow one is written
        assertTrue(reds(picture, overlay.x + narrowW + 8, overlay.y, overlay.x + wideW, overlay.y + h) <= STRAY)
    }

    private companion object {
        const val SETTLE_MS = 1_200L

        /** Codec blur may leave a few reddish pixels beside the letters. */
        const val STRAY = 8
    }
}
