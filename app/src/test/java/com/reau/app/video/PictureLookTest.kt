package com.reau.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PictureLook] and [ScreenNet] on synthetic pictures only (no frame of anybody's route is in the repository). The pictures are ones the study fed to its
 * independent reference implementation in C (a small program in C, same float32 sums): the expected logits and confidences below come from it, so
 * any change to a weight, a feature or the calibration shows here. What the net gets right on real screens was measured on 18505 recorded frames of the Android Auto 17 and 18 (see [PictureLook], which also has the retrainings of 03/11, 08/11 and 10/11/2026).
 */
class PictureLookTest {
    private val w = PictureLook.SAMPLE_W
    private val h = PictureLook.SAMPLE_H

    private fun rgba(pixel: (x: Int, y: Int) -> Int): ByteArray = ByteArray(w * h * 4).also { px ->
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val v = pixel(x, y)
            px[o] = v.toByte(); px[o + 1] = v.toByte(); px[o + 2] = v.toByte(); px[o + 3] = 0xFF.toByte()
        }
    }

    private fun flat(v: Int) = rgba { _, _ -> v }

    private fun noise(): ByteArray {
        var s = 12345L
        val px = ByteArray(w * h * 4)
        for (i in 0 until w * h * 3) {
            s = (s * 1103515245L + 12345L) and 0x7fffffffL
            val o = (i / 3) * 4 + i % 3
            px[o] = ((s shr 16) and 0xff).toByte()
        }
        for (p in 0 until w * h) px[p * 4 + 3] = 0xFF.toByte()
        return px
    }

    private fun gradient() = rgba { x, y -> (x * 7 + y * 3) % 256 }

    /** Dark on top, white below: a map for the net; turned over (white on top) it is not. */
    private fun darkOverWhite() = rgba { _, y -> if (y < h / 2) 0 else 255 }

    /** Black with white-ish bars on the left and on top: about 80% black. */
    private fun bars() = rgba { x, y -> if (x < 8 || y < 4) 200 else 0 }

    /** Gray [v] with a lighter frame (96) on the left and on top: just over the limit of 0,9 for the net. */
    private fun grayWithEdge(v: Int) = rgba { x, y -> if (x < 8 || y < 4) 96 else v }

    /** Gray [v] on the top half and [v] + 40 below: just under the limit of 0,9 for the net when [v] is 48. */
    private fun darkTop(v: Int) = rgba { _, y -> if (y < h / 2) v else v + 40 }

    private class Expected(val kind: PictureLook.Kind, val confidence: Float, val logits: FloatArray)

    private fun check(name: String, picture: ByteArray, expected: Expected) {
        assertEquals(name, expected.kind, PictureLook.of(picture, w, h))
        val result = ScreenNet.classify(picture, w, h)
        assertEquals("$name: confidence", expected.confidence, result.confidence, 1e-3f)
        assertArrayEquals("$name: calibrated logits", expected.logits, ScreenNet.lastLogits(), 1e-4f)
    }

    @Test
    fun `the net says what the independent C reference says on ten synthetic pictures`() {
        check("black", flat(0), Expected(PictureLook.Kind.UNSURE, 0f, floatArrayOf(-1.73659718f, -0.736481547f, 0.943776488f)))
        check("white", flat(255), Expected(PictureLook.Kind.MAP, 0.999717653f, floatArrayOf(5.58857489f, -4.69751549f, -2.7122376f)))
        check("gray 46", flat(46), Expected(PictureLook.Kind.APPS, 0.991819084f, floatArrayOf(-0.927077532f, -5.2747016f, 3.88351655f)))
        check("noise", noise(), Expected(PictureLook.Kind.MENU, 0.999795735f, floatArrayOf(-4.60118818f, 6.94043732f, -1.60423326f)))
        check("gradient", gradient(), Expected(PictureLook.Kind.UNSURE, 0.688916564f, floatArrayOf(0.527965426f, -1.11377537f, -0.827171803f)))
        check("black with bars", bars(), Expected(PictureLook.Kind.UNSURE, 0.798353732f, floatArrayOf(0.820433736f, -1.17719233f, -1.32582605f)))
        check("dark over white", darkOverWhite(), Expected(PictureLook.Kind.MAP, 0.999609768f, floatArrayOf(6.23001337f, -6.73973036f, -1.62428927f)))
        check("white over dark", rgba { _, y -> if (y < h / 2) 255 else 0 }, Expected(PictureLook.Kind.UNSURE, 0.510204375f, floatArrayOf(-2.0781889f, 1.18100595f, 1.09933972f)))
        check("gray 63 with a lighter edge", grayWithEdge(63), Expected(PictureLook.Kind.APPS, 0.908512115f, floatArrayOf(0.649943352f, -6.32487011f, 2.94648004f)))
        check("dark top 48", darkTop(48), Expected(PictureLook.Kind.UNSURE, 0.889621496f, floatArrayOf(1.20926499f, -7.19960833f, 3.29636836f)))
    }

    @Test
    fun `the four kinds all come out`() {
        val kinds = listOf(flat(0), flat(255), noise(), flat(46)).map { PictureLook.of(it, w, h) }
        assertEquals(listOf(PictureLook.Kind.UNSURE, PictureLook.Kind.MAP, PictureLook.Kind.MENU, PictureLook.Kind.APPS), kinds)
    }

    @Test
    fun `a picture that is not sure enough (confidence under 0,9) is unsure, not the class it leans to`() {
        val result = ScreenNet.classify(darkTop(48), w, h)
        assertTrue("the leaning class would be APPS", ScreenNet.lastLogits()[2] > ScreenNet.lastLogits()[0])
        assertEquals(ScreenNet.Screen.UNSURE, result.screen)
        assertTrue("confidence ${result.confidence}", result.confidence < ScreenNet.MIN_CONFIDENCE && result.confidence > 0.85f)
        val gray = ScreenNet.classify(grayWithEdge(63), w, h)
        assertTrue("just over the limit it is the class: confidence ${gray.confidence}", gray.confidence > ScreenNet.MIN_CONFIDENCE && gray.confidence < 0.92f)
        assertEquals(ScreenNet.Screen.APPS, gray.screen)
    }

    @Test
    fun `from 92 percent of black pixels the net is not even asked, and below it is`() {
        // a white picture (a map for the net) with some pixels painted black: 92% of 2560 = 2355.2, so 2356 black pixels shut the gate and 2355 do not
        fun withBlack(n: Int): ByteArray = rgba { x, y -> if (y * w + x < n) 0 else 255 }
        val shut = ScreenNet.classify(withBlack(2356), w, h)
        assertEquals(ScreenNet.Screen.UNSURE, shut.screen)
        assertEquals("the gate answers without a confidence", 0f, shut.confidence, 0f)
        val open = ScreenNet.classify(withBlack(2355), w, h)
        assertTrue("the net answered: confidence ${open.confidence}", open.confidence > 0f)
    }

    @Test
    fun `a pixel is black below luma 16 and not from 16`() {
        assertEquals("all at 15: black", 0f, ScreenNet.classify(flat(15), w, h).confidence, 0f)
        assertTrue("all at 16: not black, the net answers", ScreenNet.classify(flat(16), w, h).confidence > 0f)
    }

    @Test
    fun `a picture of another size or a short array is unsure`() {
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(10), w, h))
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(128 * 80 * 4), 128, 80))
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(w * h * 4 - 1), w, h))
    }

    @Test
    fun `the net looks at where things are, so a picture upside down is another picture`() {
        val upright = darkOverWhite()
        val upsideDown = rgba { _, y -> if (y < h / 2) 255 else 0 }
        assertEquals(PictureLook.Kind.MAP, PictureLook.of(upright, w, h))
        assertTrue("turned over it is not read as the map", PictureLook.of(upsideDown, w, h) != PictureLook.Kind.MAP)
        assertArrayEquals("the same two pictures, one the other upside down", upright, PictureLook.topDown(upsideDown, w, h))
        assertEquals("what glReadPixels gives, turned, is read as the picture it shows", PictureLook.Kind.MAP, PictureLook.of(PictureLook.topDown(upsideDown, w, h), w, h))
    }

    @Test
    fun `topDown puts the last row first and keeps every pixel`() {
        val bottomUp = ByteArray(2 * 3 * 4) { (it / 8).toByte() }       // 2 pixels a row, 3 rows: the rows are 0, 1, 2 from the bottom
        val top = PictureLook.topDown(bottomUp, 2, 3)
        assertArrayEquals(ByteArray(8) { 2 } + ByteArray(8) { 1 } + ByteArray(8) { 0 }, top)
        assertArrayEquals("twice is the same picture", bottomUp, PictureLook.topDown(top, 2, 3))
        assertEquals("the input is left alone", 0.toByte(), bottomUp[0])
    }

    @Test
    fun `topDown refuses an array that is not width times height of RGBA`() {
        assertThrows(IllegalArgumentException::class.java) { PictureLook.topDown(ByteArray(10), 2, 3) }
    }
}
