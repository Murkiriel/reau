package com.reau.app.dash.video

import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real MediaCodec encoder fed the way the compositor will feed it: OpenGL frames on the exposed input surface,
 * each stamped with its presentation time. No dash and no Android Auto: this is the encoder alone.
 *
 * The encoder is fed on its surface only (no Canvas `renderFrame`, no `drain` to pull with), so the cases are written
 * here on EGL.
 */
@RunWith(AndroidJUnit4::class)
class DashEncoderSurfaceTest {

    /** One buffer handed to `onFrame`. */
    private class Out(val nalTypes: List<Int>, val isKey: Boolean, val ptsMs: Long, val thread: String) {
        /** A coded picture (slice NAL), as opposed to the SPS/PPS buffer. */
        val isFrame: Boolean get() = nalTypes.any { it == NAL_SLICE || it == NAL_IDR }
    }

    private val out = CopyOnWriteArrayList<Out>()
    private var encoder: DashEncoder? = null
    private var painter: EglPainter? = null
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.YELLOW)

    @After
    fun tearDown() {
        painter?.release()
        encoder?.release()
    }

    private fun newEncoder(sink: MutableList<Out> = out) = DashEncoder { data, isKey, ptsMs ->
        sink += Out(nalTypes(data), isKey, ptsMs, Thread.currentThread().name)
    }

    /** A prepared encoder with a painter on its surface. */
    private fun start(iFrameIntervalSec: Int = 1): DashEncoder {
        val enc = newEncoder().also { encoder = it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS, iFrameIntervalSec))
        painter = EglPainter(checkNotNull(enc.inputSurface) { "a prepared encoder must expose its input surface" })
        return enc
    }

    private fun frames(sink: List<Out> = out) = sink.filter { it.isFrame }

    /** Paints [count] frames, 62 ms apart, with times [firstPtsMs], +62, +124, … */
    private fun paint(count: Int, firstPtsMs: Long, announceTo: DashEncoder? = null) {
        repeat(count) { i ->
            val ptsMs = firstPtsMs + i * STEP_MS
            announceTo?.frameSubmitted(ptsMs)
            checkNotNull(painter).paint(colors[i % colors.size], ptsMs)
            Thread.sleep(STEP_MS)
        }
    }

    /**
     * Waits for the frames just painted to come out. The default wait ends before the encoder would repeat the
     * last frame by itself ([DashEncoder.REPEAT_AFTER_US] after the last one painted), so a test that counts
     * frames only sees the ones it painted.
     */
    private fun awaitFrames(atLeast: Int, timeoutMs: Long = 700, sink: List<Out> = out) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (frames(sink).size < atLeast && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    // ── what the dash needs from the very first bytes ──────────────────────────────────────────────────────

    @Test
    fun theParameterSetsComeOutBeforeTheFirstFrameAndTheFirstFrameIsAKeyframe() {
        start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)

        val firstFrameAt = out.indexOfFirst { it.isFrame }
        assertTrue("no frame came out: ${out.map { it.nalTypes }}", firstFrameAt >= 0)
        val before = out.take(firstFrameAt).flatMap { it.nalTypes } + out[firstFrameAt].nalTypes.takeWhile { it != NAL_IDR }
        assertTrue("SPS must come before the first frame, got ${out.map { it.nalTypes }}", NAL_SPS in before)
        assertTrue("PPS must come before the first frame, got ${out.map { it.nalTypes }}", NAL_PPS in before)

        val first = out[firstFrameAt]
        assertTrue("the first frame must be flagged as a keyframe", first.isKey)
        assertTrue("the first frame must be an IDR, got ${first.nalTypes}", NAL_IDR in first.nalTypes)
    }

    @Test
    fun theStreamIsBaselineProfile() {
        val sps = CopyOnWriteArrayList<ByteArray>()
        val enc = DashEncoder { data, _, _ -> if (NAL_SPS in nalTypes(data)) sps += data }.also { encoder = it }
        assertTrue(enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        painter = EglPainter(checkNotNull(enc.inputSurface))
        paint(3, firstPtsMs = STEP_MS)
        val deadline = System.currentTimeMillis() + 700
        while (sps.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue("no SPS came out", sps.isNotEmpty())
        // profile_idc is the byte after the SPS NAL header. 66 = Baseline: the only one the dash shows.
        assertEquals(66, profileIdc(sps.first()))
    }

    /**
     * The conservative envelope (4 frames a second, 200 kbps) at a constant or a
     * variable bitrate still gives a keyframe first and every frame painted, on whatever encoder the phone has (one
     * that does not offer the mode falls back, DashEncoder.rateMode).
     */
    @Test
    fun eachBitrateModeAtTheConservativeEnvelopeStillCodesEveryFrameFromAKeyframe() {
        for (mode in com.reau.app.dash.BitrateMode.entries) {
            out.clear()
            painter?.release()
            encoder?.release()
            val enc = newEncoder().also { encoder = it }
            assertTrue("$mode: prepare failed: ${enc.lastPrepareError}", enc.prepare(4, 200_000, 1, mode))
            painter = EglPainter(checkNotNull(enc.inputSurface))
            paint(8, firstPtsMs = STEP_MS)
            awaitFrames(8)
            val coded = frames()
            assertTrue("$mode: only ${coded.size}/8 frames came out", coded.size >= 7)
            assertTrue("$mode: the first frame must be a keyframe", coded.first().isKey)
        }
    }

    // ── the time of each frame ─────────────────────────────────────────────────────────────────────────────

    /** Nobody calls frameSubmitted here: the time travels through the codec itself, with the picture. */
    @Test
    fun everyFrameComesBackWithTheTimeItWasStampedWithOnTheSurface() {
        start()
        val submitted = 20
        paint(submitted, firstPtsMs = STEP_MS)
        awaitFrames(submitted)

        val times = frames().map { it.ptsMs }
        assertTrue("only ${times.size}/$submitted frames came out", times.size >= submitted * 9 / 10)
        assertEquals("every frame must carry a different time", times.size, times.toSet().size)
        assertEquals("times must come out in submission order", times.sorted(), times)
        assertTrue("times must be ones that were stamped, got $times", times.all { it % STEP_MS == 0L && it in STEP_MS..submitted * STEP_MS })
        assertTrue(
            "frames must arrive on the encoder's own thread, got ${out.map { it.thread }.toSet()}",
            out.all { it.thread == EncoderOutputPump.THREAD_NAME },
        )
    }

    @Test
    fun theStatsCountTheFramesTheCompositorAnnounced() {
        val enc = start()
        val submitted = 10
        paint(submitted, firstPtsMs = STEP_MS, announceTo = enc)
        awaitFrames(submitted)
        val stats = enc.collectOutput() // also throws if the output thread failed

        assertEquals("the stats must count the frames that came out", frames().size, stats.frames)
        assertTrue("only ${stats.frames}/$submitted frames came out", stats.frames >= submitted * 9 / 10)
        assertTrue("time in the encoder must have been measured, got ${stats.latencySumNs}", stats.latencySumNs > 0)
        assertEquals("the count starts again after it is taken", 0, enc.collectOutput().frames)
    }

    // ── keyframes on request, and the safety net ───────────────────────────────────────────────────────────

    @Test
    fun aSyncFrameRequestIsAnsweredWithAKeyframe() {
        // 30 s between the encoder's own keyframes, so the only ones in this test are the first and the requested.
        val enc = start(iFrameIntervalSec = 30)
        paint(10, firstPtsMs = STEP_MS)
        awaitFrames(10)
        val before = frames().count { it.isKey }
        assertEquals("only the opening keyframe before the request", 1, before)

        enc.requestSyncFrame()
        paint(10, firstPtsMs = 11 * STEP_MS)
        awaitFrames(20)

        val keys = frames().filter { it.isKey }
        assertEquals("the request must produce exactly one more keyframe", 2, keys.size)
        assertTrue("the requested keyframe must be one of the frames painted after the request", keys.last().ptsMs >= 11 * STEP_MS)
    }

    @Test
    fun whenNothingIsPaintedTheEncoderRepeatsTheLastFrameByItself() {
        start()
        paint(3, firstPtsMs = STEP_MS)
        awaitFrames(3)
        val painted = frames().size
        assertEquals(3, painted)

        // Nothing is painted from here on. The codec must encode the last frame again after REPEAT_AFTER_US.
        awaitFrames(painted + 1, timeoutMs = 4 * DashEncoder.REPEAT_AFTER_US / 1000)

        val all = frames()
        assertTrue("no frame came out by itself in ${4 * DashEncoder.REPEAT_AFTER_US / 1000} ms", all.size > painted)
        assertTrue("a repeated frame must carry a later time than the last painted one", all[painted].ptsMs > 3 * STEP_MS)
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theSurfaceExistsOnlyWhileTheEncoderIsPrepared() {
        val enc = newEncoder().also { encoder = it }
        assertNull("no surface before prepare", enc.inputSurface)
        assertTrue(enc.prepare())
        assertNotNull(enc.inputSurface)
        assertTrue(checkNotNull(enc.inputSurface).isValid)
        assertNull(enc.lastPrepareError)
        enc.release()
        assertNull("no surface after release", enc.inputSurface)
    }

    @Test
    fun releaseStopsTheOutputThread() {
        val enc = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)
        assertFalse(out.isEmpty())

        painter?.release()
        painter = null
        enc.release()
        val afterRelease = out.size
        // Longer than the repeat interval: a codec still running would hand over a repeated frame.
        Thread.sleep(DashEncoder.REPEAT_AFTER_US / 1000 + 400)
        assertEquals("nothing may arrive after release()", afterRelease, out.size)
    }

    /**
     * Every rate change builds a new encoder, so it must be rebuildable more times than the device has encoder
     * instances. On a phone's hardware encoder this is what a codec left running by release() would break. On the
     * emulator it does NOT prove that: its software encoder kept accepting new instances with release() mutated to
     * leave the codec running (a mutation where release does not stop the codec). Here it only shows
     * that building and releasing in a row works.
     */
    @Test
    fun theEncoderCanBeRebuiltMoreTimesThanTheDeviceHasInstances() {
        val instances = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .maxOf { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances }
        val cycles = instances + 4
        repeat(cycles) { i ->
            val enc = newEncoder()
            try {
                assertTrue("prepare ${i + 1} of $cycles failed ($instances instances): ${enc.lastPrepareError}", enc.prepare())
            } finally {
                enc.release()
            }
        }
    }

    /** A rate change prepares the new encoder BEFORE releasing the old one, so a failed prepare can keep the
     *  stream alive on the old encoder. */
    @Test
    fun aSecondEncoderCanBePreparedWhileTheFirstRunsAndThenTakesOverAlone() {
        val old = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)

        val fromNew = CopyOnWriteArrayList<Out>()
        val fresh = newEncoder(fromNew)
        try {
            assertTrue("second encoder must prepare while the first is still running", fresh.prepare(8, DashConfig.MAX_STREAM_BITRATE_BPS))
        } finally {
            painter?.release()
            painter = null
            old.release()
            encoder = fresh
        }
        val fromOldAtSwap = out.size
        painter = EglPainter(checkNotNull(fresh.inputSurface))
        paint(6, firstPtsMs = 1_000)
        awaitFrames(6, sink = fromNew)
        fresh.collectOutput()

        assertEquals("nothing from the old encoder after its release()", fromOldAtSwap, out.size)
        assertTrue("the new encoder must send frames, got ${frames(fromNew).size}", frames(fromNew).size >= 5)
        assertTrue("the new encoder must open with a keyframe", frames(fromNew).first().isKey)
    }

    /** The frame loop's "N failures in a row, rebuild the encoder" recovery depends on hearing about these. */
    @Test
    fun aFailureWhileHandingOverAFrameIsReportedOnceAndTheThreadCarriesOn() {
        val failNext = AtomicBoolean(true)
        val delivered = AtomicInteger(0)
        val enc = DashEncoder { _, _, _ ->
            if (failNext.getAndSet(false)) error("send failed") else delivered.incrementAndGet()
        }.also { encoder = it }
        assertTrue(enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        painter = EglPainter(checkNotNull(enc.inputSurface))
        paint(6, firstPtsMs = STEP_MS)
        val deadline = System.currentTimeMillis() + 700
        while (delivered.get() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        val failure = assertThrows(IllegalStateException::class.java) { enc.collectOutput() }
        assertTrue("got: ${failure.message}", failure.message.orEmpty().contains("send failed"))
        enc.collectOutput() // reported once: the second call does not throw
        assertTrue("the output thread must carry on after the failure, delivered ${delivered.get()}", delivered.get() >= 5)
    }

    @Test
    fun requestsOnAnUnpreparedEncoderAreNoOpsNotCrashes() {
        val enc = newEncoder().also { encoder = it }
        enc.requestSyncFrame()
        enc.requestBitrate(100_000)
        enc.frameSubmitted(1)
        assertEquals(0, enc.collectOutput().frames)
        enc.release()
    }

    @Test
    fun theBitrateCanBeChangedOnARunningEncoder() {
        val enc = start()
        paint(5, firstPtsMs = STEP_MS)
        awaitFrames(5)
        enc.requestBitrate(100_000)
        paint(5, firstPtsMs = 6 * STEP_MS)
        awaitFrames(10)
        enc.collectOutput() // throws if the output thread failed
        assertTrue("frames must keep coming out after requestBitrate(), got ${frames().size}", frames().size >= 9)
    }

    private companion object {
        const val STEP_MS = 62L
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        /** NAL unit types of an Annex-B buffer, in order (after each 00 00 01 start code). */
        fun nalTypes(data: ByteArray): List<Int> = startCodes(data).map { data[it].toInt() and 0x1F }

        /** profile_idc of the first SPS in [data]. */
        fun profileIdc(data: ByteArray): Int {
            val at = startCodes(data).first { data[it].toInt() and 0x1F == NAL_SPS }
            return data[at + 1].toInt() and 0xFF
        }

        /** Index of the NAL header byte after each start code. */
        private fun startCodes(data: ByteArray): List<Int> = (0..data.size - 4).filter {
            data[it].toInt() == 0 && data[it + 1].toInt() == 0 && data[it + 2].toInt() == 1
        }.map { it + 3 }
    }
}
