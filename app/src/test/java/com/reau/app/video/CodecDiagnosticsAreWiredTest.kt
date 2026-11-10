package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The codec and OpenGL diagnostics of the ride log (plan 23) live in classes that need Android to run (the decoder, the encoder, the GL
 * compositor), so the lines that wire them are read from the source, as [com.reau.app.service.CompositorBusyIsWiredTest] does. What they
 * print is covered by the JVM tests of the pure functions ([com.reau.app.util.CodecReportTest], `CodecMetricsTest`, `CodecErrorsTest`,
 * `StageTimesTest`). None of it changes what is drawn, decoded or encoded.
 */
class CodecDiagnosticsAreWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val decoder = source("aa/VideoDecoder.kt")
    private val encoder = source("dash/video/DashEncoder.kt")
    private val pump = source("dash/video/EncoderOutputPump.kt")
    private val compositor = source("video/DashCompositor.kt")
    private val loop = source("stream/VideoLoop.kt")

    @Test
    fun `the decoder reads its metrics before the bounded release, and a hang of the read costs the end of the session no more than its limit`() {
        val stop = decoder.substringAfter("fun stop(reason: String").substringBefore("codec = null")
        assertTrue(stop.contains("CodecMetrics.logBounded(\"decoder\", currentCodecName, held)"))
        assertTrue("before the release", stop.indexOf("CodecMetrics.logBounded") < stop.indexOf("held.release()"))
        assertTrue("the release stays inside BoundedRelease", stop.indexOf("BoundedRelease.run(") < stop.indexOf("held.release()"))
        val metrics = source("util/CodecMetrics.kt").substringAfter("fun logBounded(")
        assertTrue(metrics.contains("BoundedRelease.run("))
        assertTrue(metrics.contains("joinMs = JOIN_MS"))
    }

    @Test
    fun `the encoder reads its metrics before it stops the codec, inside the release that waits for the output thread`() {
        val release = encoder.substringAfter("fun release() {")
        assertTrue(release.contains("EncoderOutputPump.releaseThrough(ending) {"))
        assertTrue(release.indexOf("CodecMetrics.logBounded(\"encoder\", label, c)") in release.indexOf("releaseThrough") until release.indexOf("c?.stop()"))
    }

    @Test
    fun `a codec failure is told with its code, whether it recovers and whether it is transient, in the decoder, the encoder's start and its output thread`() {
        assertEquals(3, Regex("CodecErrors\\.describe\\(e, currentCodecName\\)").findAll(decoder).count())
        assertTrue(encoder.contains("CodecErrors.describe(it, name)"))
        assertTrue(pump.contains("CodecErrors.describe(it, codecName)"))
    }

    @Test
    fun `the codecs line is told once for the decoder and once for a stream's first encoder, never for a rebuilt one`() {
        assertTrue(decoder.contains("if (chosen == reportedCodec) return"))
        assertTrue(encoder.contains("if (reportCodecs) EncoderReport.choice(c, candidateReport)"))
        assertTrue(encoder.contains("if (reportCodecs) EncoderReport.outputAnnounced(format, inputFormat, f)"))
        val start = loop.substringAfter("fun start(): Boolean {").substringBefore("stats?.panelStarted()")
        assertTrue("start asks for the line, just before it builds the first encoder", start.indexOf("reportCodecsOfNextEncoder = true") in 0 until start.indexOf("buildEncoder("))
        assertFalse("a rebuilt encoder does not", loop.substringAfter("private fun rebuild(").substringBefore("private fun logThermal").contains("reportCodecsOfNextEncoder = true"))
        assertTrue(loop.contains("created.reportCodecs = reportCodecsOfNextEncoder\n        reportCodecsOfNextEncoder = false"))
    }

    @Test
    fun `the encoder's accepted line is built when the codec announces its output format, on the output thread, from the input format read after start`() {
        val start = encoder.substringAfter("private fun startCodec(").substringBefore("}.onFailure")
        assertTrue("the input format is read after start", start.indexOf("c.start()") < start.indexOf("EncoderReport.inputFormat(c)"))
        assertTrue("the line comes from the pump's format callback, not the frame loop", start.indexOf("EncoderOutputPump(c, onFormat =") < start.indexOf("EncoderReport.outputAnnounced("))
        assertFalse("nothing in the frame path reports", encoder.substringAfter("internal fun collectOutput()").contains("EncoderReport"))
        assertTrue(pump.contains("if (it == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) runCatching { onFormat(codec.outputFormat) }"))
        // when the codec announces its output, both lines are told: the output format as it is, and the accepted one (output over input)
        val announced = source("dash/video/EncoderReport.kt").substringAfter("fun outputAnnounced(").substringBefore("/** The codec's output format")
        assertTrue(announced.contains("outputFormat(output)"))
        assertTrue(announced.contains("accepted(asked, input, output)"))
    }

    @Test
    fun `the codec list is read when a stream starts, never by the compositor that draws every frame`() {
        assertFalse(compositor.contains("MediaCodecList"))
        // The decoder's single look at its own choice, after the one that picks (kept by CodecNames for the process).
        assertEquals(1, Regex("MediaCodecList\\(").findAll(decoder.substringAfter("private fun reportChoice")).count())
    }

    @Test
    fun `the look and the latch are timed apart from the compose, and every wakeup is counted`() {
        val callback = compositor.substringAfter("inputSurface = gl.createInput(").substringBefore("ok = true")
        assertTrue("every wakeup is counted, before the latch", callback.indexOf("wakeups.incrementAndGet()") in 0 until callback.indexOf("gl.latch()"))
        assertTrue(callback.contains("latchClock.add("))
        val look = compositor.substringAfter("private fun takeLook()").substringBefore("fun busyMs()")
        assertTrue(look.contains("lookClock.add("))
        assertTrue("the look's timing is outside the draw", look.indexOf("lookClock.add(") < look.indexOf("listener(kind"))
        val draw = compositor.substringAfter("private fun drawAndPost").substringBefore("private val webIdle")
        assertTrue(draw.contains("composeClock.add(composedNs)"))
        assertFalse("the compose does not count the look", draw.contains("lookClock"))
        assertTrue(compositor.contains("composeClock.take(), lookClock.take(), latchClock.take(), wakeups.getAndSet(0)"))
    }

    @Test
    fun `the video loop hands the encoder's frame times to the governor`() {
        assertTrue(loop.contains("encode = timed?.latency ?: StageStats.EMPTY"))
    }
}
