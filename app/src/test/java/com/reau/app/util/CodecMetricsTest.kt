package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `MediaCodec.getMetrics()` in one compact line (the bundle's keys vary by vendor and Android version). */
class CodecMetricsTest {
    private val prefix = "android.media.mediacodec."

    private fun bundle(vararg pairs: Pair<String, Any?>): Map<String, Any?> = pairs.associate { (k, v) -> prefix + k to v }

    @Test
    fun `the known keys come first without their long prefix, the latencies in milliseconds`() {
        val line = CodecMetrics.line(
            "decoder", "c2.qti.avc.decoder",
            bundle(
                "latency.max" to 98_000L, "latency.avg" to 9_200L, "latency.min" to 3_100L, "latency.n" to 1503L, "latency.unknown" to 0L,
                "codec" to "c2.qti.avc.decoder", "mime" to "video/avc", "mode" to "video", "encoder" to 0, "secure" to 0, "width" to 800, "height" to 480,
                "errcode" to 0, "errstate" to "OK",
            ),
        )
        assertEquals(
            "codec metrics (decoder c2.qti.avc.decoder): codec=c2.qti.avc.decoder mime=video/avc mode=video encoder=0 secure=0 width=800 height=480 " +
                "errcode=0 errstate=OK latency=n 1503 avg 9.2ms min 3.1ms max 98.0ms unknown 0",
            line,
        )
    }

    @Test
    fun `keys about errors, drops, counts and frames follow, eight at most, and the rest are counted`() {
        val extras = (0..9).map { "dropped$it" to it }.toTypedArray()
        val line = CodecMetrics.line("encoder", "c2.qti.avc.encoder", bundle("codec" to "c2.qti.avc.encoder", "queueinputbuffer.count" to 7L, "other" to 1, *extras))
        assertTrue(line, "dropped0=0" in line && "dropped7=7" in line)
        assertFalse(line, "dropped8=8" in line || "other=1" in line || "queueinputbuffer.count" in line)
        // 13 keys: 1 known and 8 shown; the other 4 are counted.
        assertTrue(line, line.endsWith(" +4 keys"))
    }

    @Test
    fun `a key about errors is shown when there is room`() {
        val line = CodecMetrics.line("decoder", "a", bundle("codec" to "a", "queueinputbuffer.count" to 7L, "other" to 1))
        assertEquals("codec metrics (decoder a): codec=a queueinputbuffer.count=7 +1 keys", line)
    }

    @Test
    fun `arrays and long text do not inflate the line, and a double has two decimals`() {
        val line = CodecMetrics.line("encoder", null, bundle("codec" to "x y\nz", "frame-rate" to 3.456, "profile" to longArrayOf(1, 2)))
        assertEquals("codec metrics (encoder ?): codec=x_y_z frame-rate=3.46 profile=[…]", line)
    }

    @Test
    fun `an empty bundle says none`() {
        assertEquals("codec metrics (decoder a): none", CodecMetrics.line("decoder", "a", emptyMap()))
    }
}
