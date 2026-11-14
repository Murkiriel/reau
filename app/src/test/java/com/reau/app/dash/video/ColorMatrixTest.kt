package com.reau.app.dash.video

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The colour keys of the dash's encoder, on the experimental switch: none without it, BT.601 limited SDR with it, and the way back when the codec refuses. */
class ColorMatrixTest {
    @Test
    fun `without the switch no colour key is set`() {
        assertEquals(emptyMap<String, Int>(), ColorMatrix.keysFor(false))
    }

    @Test
    fun `with it the format asks BT_601, limited range and SDR transfer`() {
        val keys = ColorMatrix.keysFor(true)
        assertEquals(
            mapOf(
                MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT601_NTSC,
                MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED,
                MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            ),
            keys,
        )
        assertEquals("BT.601 limited-range SDR", ColorMatrix.ASKED)
    }

    @Test
    fun `off, the colour attempt is never run and the encoder starts as it always did`() {
        var colour = 0
        var before = 0
        val result = ColorMatrix.attempt(on = false, withColor = { colour++; Result.success("colour") }, asBefore = { before++; Result.success("as before") }, refused = { error("not asked") })
        assertEquals("as before", result.getOrThrow())
        assertEquals(0, colour)
        assertEquals(1, before)
    }

    @Test
    fun `on, a codec that takes the colour keys is started with them and the old way is not tried`() {
        var before = 0
        val result = ColorMatrix.attempt(on = true, withColor = { Result.success("colour") }, asBefore = { before++; Result.success("as before") }, refused = { error("not refused") })
        assertEquals("colour", result.getOrThrow())
        assertEquals(0, before)
    }

    @Test
    fun `on, a codec that refuses them is asked again as before, and the refusal is told`() {
        val refusals = mutableListOf<Throwable>()
        val boom = IllegalStateException("no")
        val result = ColorMatrix.attempt(on = true, withColor = { Result.failure<String>(boom) }, asBefore = { Result.success("as before") }, refused = { refusals += it })
        assertEquals("as before", result.getOrThrow())
        assertEquals(listOf<Throwable>(boom), refusals)
    }

    @Test
    fun `on, a codec that refuses both is a failure of the old way, which the encoder's own fallbacks have already tried`() {
        val result = ColorMatrix.attempt(on = true, withColor = { Result.failure<String>(IllegalStateException("a")) }, asBefore = { Result.failure(IllegalArgumentException("b")) }, refused = {})
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `the log says what the codec echoed of each key`() {
        val echoed = mapOf(MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        assertEquals(
            "encoder colour: asked BT.601 limited-range SDR; the codec says standard=BT.601-NTSC, range=limited, transfer=SDR",
            ColorMatrix.acceptedLine { echoed[it] },
        )
        assertEquals(
            "encoder colour: asked BT.601 limited-range SDR; the codec says standard not echoed, range not echoed, transfer not echoed",
            ColorMatrix.acceptedLine { null },
        )
    }

    @Test
    fun `a key echoed with another value is told with what was asked`() {
        val line = ColorMatrix.acceptedLine {
            when (it) {
                MediaFormat.KEY_COLOR_STANDARD -> MediaFormat.COLOR_STANDARD_BT709
                MediaFormat.KEY_COLOR_RANGE -> MediaFormat.COLOR_RANGE_FULL
                else -> null
            }
        }
        assertTrue(line, "standard=BT.709 (asked BT.601-NTSC)" in line && "range=full (asked limited)" in line && "transfer not echoed" in line)
    }

    @Test
    fun `the refusal line names the encoder, the keys and the error`() {
        assertEquals(
            "encoder c2.vendor.avc.encoder refused the colour keys (BT.601 limited-range SDR): IllegalArgumentException; asked again without them",
            ColorMatrix.refusedLine("c2.vendor.avc.encoder", "IllegalArgumentException"),
        )
        assertTrue(ColorMatrix.refusedLine(null, "x").startsWith("encoder OS-default encoder refused"))
    }

    @Test
    fun `the encoder sets the keys only for the format it asks with colour, and the loop passes the switch down`() {
        val encoder = File("src/main/java/com/reau/app/dash/video/DashEncoder.kt").readText()
        assertTrue(encoder.contains("ColorMatrix.keysFor(color).forEach { (k, v) -> setInteger(k, v) }"))
        assertTrue(encoder.contains("buildFormat(ask, lowLatency = true, rate.constant, color = true)"))
        val oldWay = encoder.substringAfter("fun asBefore()").substringBefore("return ColorMatrix.attempt(")
        assertFalse("the formats of the old way carry no colour", oldWay.contains("color") || oldWay.contains("colour"))
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("colorMatrix = settings.colorMatrix"))
        assertTrue(loop.contains("colorMatrix = rider.experimentColorMatrix"))
        val report = File("src/main/java/com/reau/app/dash/video/EncoderReport.kt").readText()
        assertTrue("only when the keys were asked", report.contains("if (!asked.containsKey(MediaFormat.KEY_COLOR_STANDARD)) return"))
    }
}
