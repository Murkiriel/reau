package com.reau.app.aa

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.video.H264TestDecoder
import com.reau.app.video.PictureCheck
import com.reau.app.video.SoftwareH264Decoder
import com.reau.app.video.TestH264Source
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The copied H.264 decoder on its own, on the emulator: fed the video a fake Android Auto would send, rendering on
 * a surface the test reads back. Characterisation, written AFTER the code.
 */
@RunWith(AndroidJUnit4::class)
class VideoDecoderTest {
    /** The pictures are read back through an ImageReader: from the software decoder, which every device can read (see the rule). */
    @get:org.junit.Rule val softwareDecoder = SoftwareH264Decoder()

    private val log = CopyOnWriteArrayList<String>()
    private val decoder = VideoDecoder()
    private val readerThread = HandlerThread("test-image-reader").apply { start() }
    private val reader = ImageReader.newInstance(TestPattern.WIDTH, TestPattern.HEIGHT, ImageFormat.YUV_420_888, 4)
    private val lastPicture = AtomicReference<H264TestDecoder.Picture?>()
    private val pictures = AtomicInteger()
    private val decoderErrors = AtomicInteger()
    private val lost = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        AaLog.sink = { log += it }
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use {
                lastPicture.set(H264TestDecoder.pictureOf(it))
                pictures.incrementAndGet()
            }
        }, Handler(readerThread.looper))
        decoder.onDecoderError = { decoderErrors.incrementAndGet() }
        decoder.onFrameLost = { lost += it }
        decoder.setSurface(reader.surface)
    }

    @After
    fun tearDown() {
        decoder.stop("test over")
        reader.close()
        readerThread.quitSafely()
        AaLog.sink = null
    }

    private fun feed(units: List<TestH264Source.Unit>, gapMs: Long = 0) {
        for (unit in units) {
            decoder.decode(unit.data, 0, unit.data.size)
            if (gapMs > 0) Thread.sleep(gapMs)
        }
    }

    /**
     * Feeds filler-data NAL units (type 12: valid H.264 that carries no picture) ten times a second until the
     * decoder reports itself broken. The watchdog needs three seconds of input with no gap of a second in it, and
     * the emulator sometimes stops everything for longer than that: the budget leaves room for the three seconds
     * to start over. Returns what happened, for the failure message.
     */
    private val filler = byteArrayOf(0, 0, 0, 1, 0x0C, 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte())

    private fun feedFillerUntilTheDecoderGivesUp(times: Int = 1): String {
        val start = System.currentTimeMillis()
        var last = start
        var count = 0
        var longestGap = 0L
        while (decoderErrors.get() < times && System.currentTimeMillis() - start < FILLER_BUDGET_MS) {
            decoder.decode(filler, 0, filler.size)
            val now = System.currentTimeMillis()
            longestGap = maxOf(longestGap, now - last)
            last = now
            count++
            Thread.sleep(100)
        }
        return "$count fillers in ${last - start} ms, longest gap between two $longestGap ms"
    }

    private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (log: $log)", condition())
    }

    /** Every cell of the pattern, looked at in its middle. */
    private fun assertShowsThePattern(picture: H264TestDecoder.Picture) {
        assertEquals(TestPattern.WIDTH, picture.width)
        assertEquals(TestPattern.HEIGHT, picture.height)
        val wrong = mutableListOf<String>()
        for (y in 40 until TestPattern.HEIGHT step 80) {
            for (x in 50 until TestPattern.WIDTH step 100) {
                val expected = PictureCheck.name(TestPattern.colorAt(x, y))
                val actual = PictureCheck.name(picture.colorAt(x, y))
                if (actual != expected) wrong += "($x,$y) $actual, expected $expected"
            }
        }
        assertTrue("${wrong.size} of 48 cells wrong: $wrong", wrong.isEmpty())
    }

    @Test
    fun framesFedToTheDecoderComeOutOnTheSurfaceAsThePictureThatWentIn() {
        val firstFrames = AtomicInteger()
        decoder.onFirstFrameListener = { firstFrames.incrementAndGet() }
        val sizes = mutableListOf<Pair<Int, Int>>()
        decoder.dimensionsListener = object : VideoDimensionsListener {
            override fun onVideoDimensionsChanged(width: Int, height: Int) {
                sizes += width to height
            }
        }

        feed(TestH264Source.encode(frames = 10), gapMs = 20)

        await("pictures must reach the surface") { pictures.get() >= 5 }
        assertShowsThePattern(checkNotNull(lastPicture.get()))
        assertEquals("the size is read from the SPS", 800 to 480, decoder.videoWidth to decoder.videoHeight)
        assertEquals(listOf(800 to 480), sizes.distinct())
        assertEquals("the first-frame listener fires once", 1, firstFrames.get())
        assertTrue(decoder.lastFrameRenderedMs > 0)
        assertTrue("got $log", log.any { "Codec initialized" in it })
        assertEquals(0, decoderErrors.get())
    }

    /** Android Auto stops sending video for seconds when its app list opens or a call comes in. */
    @Test
    fun aPauseInTheInputIsNotAStallAndDoesNotRestartTheDecoder() {
        val units = TestH264Source.encode(frames = 12)
        feed(units.take(7), gapMs = 30) // config and 6 frames
        await("pictures before the pause") { pictures.get() >= 3 }
        Thread.sleep(500) // what was fed is out by now
        val renderedBefore = decoder.lastFrameRenderedMs

        Thread.sleep(4_500) // longer than the 3 s the watchdog allows a stalled decoder
        assertEquals("nothing was rendered during the pause", renderedBefore, decoder.lastFrameRenderedMs)

        val before = pictures.get()
        feed(units.drop(7), gapMs = 30) // the stream resumes, with no keyframe
        await("pictures after the pause") { pictures.get() > before }

        assertEquals("the decoder must not have been restarted", 0, decoderErrors.get())
        assertTrue("got $log", log.none { "stall" in it || "restart" in it.lowercase() })
        assertEquals("one decoder for the whole test", 1, log.count { "Codec initialized" in it })
    }

    /** The opposite case: bytes keep arriving and nothing comes out. */
    @Test
    fun inputThatProducesNoOutputForThreeSecondsRestartsTheDecoderAndAsksForAKeyframe() {
        val units = TestH264Source.encode(frames = 6)
        feed(units, gapMs = 30)
        await("pictures before the stall") { pictures.get() >= 3 }

        val fed = feedFillerUntilTheDecoderGivesUp()

        assertEquals("the decoder must report itself broken once ($fed, log: $log)", 1, decoderErrors.get())
        assertTrue("got $log", log.any { "Decoder stall detected" in it })
        assertTrue("got $log", log.any { "Decoder stopped: restart: sync_stall" in it })

        // What Android Auto sends when asked for a keyframe, here with no codec configuration in front of it: the
        // restarted decoder still has the one it was given.
        val before = pictures.get()
        feed(TestH264Source.encode(frames = 5).filter { !it.isConfig }, gapMs = 30)
        await("pictures after the restart") { pictures.get() > before }
        assertShowsThePattern(checkNotNull(lastPicture.get()))
    }

    /**
     * The decoder draws into the compositor: while that is stuck inside one frame, bytes in and nothing out are not the decoder's fault
     * and it is left alone. The wait is a real stall all along: the moment the compositor is free, the decoder is restarted at once.
     */
    @Test
    fun aStallWhileTheCompositorIsStuckInsideAFrameLeavesTheDecoderAloneUntilItIsFree() {
        val busy = AtomicLong(5_000)
        decoder.downstreamBusyMs = { busy.get() }
        feed(TestH264Source.encode(frames = 6), gapMs = 30)
        await("pictures before the stall") { pictures.get() >= 3 }

        val until = System.currentTimeMillis() + 5_000 // the stall is called after 3 s
        while (System.currentTimeMillis() < until) {
            decoder.decode(filler, 0, filler.size)
            Thread.sleep(100)
        }
        assertEquals("the decoder was restarted while the compositor was stuck (log: $log)", 0, decoderErrors.get())
        assertTrue("got $log", log.none { "Decoder stall detected" in it })

        busy.set(0)
        val fed = feedFillerUntilTheDecoderGivesUp()
        assertEquals("the stall that was excused must be restarted once the compositor is free ($fed, log: $log)", 1, decoderErrors.get())
        assertTrue("got $log", log.any { "Decoder stall detected" in it })
    }

    /** A-15: a unit larger than the decoder's input buffer is dropped whole and a keyframe asked for at once, not cut and fed. */
    @Test
    fun aUnitLargerThanTheInputBufferIsDroppedAndAKeyframeAskedFor() {
        feed(TestH264Source.encode(frames = 6), gapMs = 30)
        await("pictures before the large unit") { pictures.get() >= 3 }

        val large = ByteArray(6 * 1024 * 1024).also { byteArrayOf(0, 0, 0, 1, 0x41).copyInto(it) }
        decoder.decode(large, 0, large.size)

        assertEquals("a keyframe asked for at once (log: $log)", 1, decoderErrors.get())
        assertTrue("got $log", log.any { "Frame too large" in it && "dropped" in it })
        val before = pictures.get()
        feed(TestH264Source.encode(frames = 5).filter { !it.isConfig }, gapMs = 30)
        await("pictures after the keyframe") { pictures.get() > before }
        assertEquals("one decoder for the whole test", 1, log.count { "Codec initialized" in it })
    }

    @Test
    fun aDecoderThatKeepsGivingPicturesIsLeftAloneHoweverLongTheInputFlows() {
        feed(TestH264Source.encode(frames = 45), gapMs = 100) // 4.5 s of input with no pause in it

        await("pictures must reach the surface") { pictures.get() >= 20 }
        assertEquals("log: $log", 0, decoderErrors.get())
        assertTrue("got $log", log.none { "stall" in it })
        assertEquals(1, log.count { "Codec initialized" in it })
    }

    /** As copied, the watchdog only began after a decoder's first picture: one stuck from the start stayed stuck. */
    @Test
    fun aDecoderThatNeverGaveAPictureIsRestartedToo() {
        feed(TestH264Source.encode(frames = 1).filter { it.isConfig }) // the decoder opens; no picture yet

        val fed = feedFillerUntilTheDecoderGivesUp()

        assertEquals("pictures: ${pictures.get()}, $fed, log: $log", 1, decoderErrors.get())
        assertEquals(0, pictures.get())
        assertTrue("got $log", log.any { "Decoder stopped: restart: sync_stall" in it })
    }

    @Test
    fun withNoSurfaceNothingIsDecodedButTheSizeIsStillRead() {
        decoder.setSurface(null)
        feed(TestH264Source.encode(frames = 3))
        Thread.sleep(300)

        assertEquals(0, pictures.get())
        assertEquals(800 to 480, decoder.videoWidth to decoder.videoHeight)
        assertTrue("no codec without a surface, got $log", log.none { "Codec initialized" in it })
    }

    @Test
    fun aNewSurfaceStopsTheDecoderAndTheNextConfigurationStartsItAgain() {
        feed(TestH264Source.encode(frames = 4), gapMs = 30)
        await("pictures on the first surface") { pictures.get() >= 1 }

        val second = ImageReader.newInstance(TestPattern.WIDTH, TestPattern.HEIGHT, ImageFormat.YUV_420_888, 4)
        val onSecond = AtomicInteger()
        second.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { onSecond.incrementAndGet() } }, Handler(readerThread.looper))
        try {
            decoder.setSurface(second.surface)
            assertTrue("got $log", log.any { "Decoder stopped: New surface" in it })
            feed(TestH264Source.encode(frames = 4), gapMs = 30)
            await("pictures on the second surface") { onSecond.get() >= 1 }
            assertEquals(2, log.count { "Codec initialized" in it })
        } finally {
            decoder.stop("test over")
            second.close()
        }
    }

    /**
     * A decoder that starts well and stalls, restarts and stalls again is given up: the second stall says the software decoder is
     * next. (Here the decoder is the software one already, so the test sees the line and the count, not a different codec.)
     */
    @Test
    fun aSecondStallInTheSessionSaysTheSoftwareDecoderIsNext() {
        feed(TestH264Source.encode(frames = 6), gapMs = 30)
        await("pictures before the stalls") { pictures.get() >= 3 }

        val fed = feedFillerUntilTheDecoderGivesUp(times = 1)
        assertEquals("the first stall ($fed, log: $log)", 1, decoderErrors.get())
        assertTrue("one stall is no reason to give up the hardware decoder, got $log", log.none { "Decoder stalled twice" in it })

        val fedAgain = feedFillerUntilTheDecoderGivesUp(times = 2)
        assertEquals("the second stall ($fedAgain, log: $log)", 2, decoderErrors.get())
        assertEquals("said once, got $log", 1, log.count { "Decoder stalled twice: the software decoder for the rest of the session" in it })
    }

    /** Pictures that came while there was no codec (here, no surface): the codec starts in the middle of a chain, and a keyframe is asked for, once. */
    @Test
    fun aCodecThatStartsOnAPictureAfterDroppedOnesAsksForAKeyframeOnce() {
        decoder.setSurface(null)
        val units = TestH264Source.encode(frames = 6)
        feed(units.take(3)) // the configuration, the keyframe and one more, with nowhere to draw
        assertTrue("nothing was lost yet: the pictures are only dropped", lost.isEmpty())

        decoder.setSurface(reader.surface)
        feed(listOf(units.first { !it.isConfig && !it.isKey }), gapMs = 30)

        assertEquals("asked once, when the codec started ($log)", 1, lost.size)
        assertTrue("got $lost", "dropped before the decoder started" in lost.single())
        feed(units.drop(4), gapMs = 30)
        assertEquals("not again for the pictures that follow", 1, lost.size)
    }

    @Test
    fun aCodecThatStartsOnAKeyframeAfterDroppedPicturesAsksForNothing() {
        decoder.setSurface(null)
        val units = TestH264Source.encode(frames = 6)
        feed(units.take(1)) // the configuration, dropped
        decoder.setSurface(reader.surface)
        feed(units.drop(1), gapMs = 30) // the keyframe starts the codec

        await("pictures from the keyframe") { pictures.get() >= 1 }
        assertTrue("the chain is whole: nothing asked ($lost)", lost.isEmpty())
    }

    @Test
    fun aDecoderThatThrowsOnAPictureSaysItWasLost() {
        feed(TestH264Source.encode(frames = 6), gapMs = 30)
        await("pictures before the failure") { pictures.get() >= 3 }
        assertTrue(lost.isEmpty())

        // The codec is taken away under the decoder: its next dequeue throws IllegalStateException.
        val codec = VideoDecoder::class.java.getDeclaredField("codec").apply { isAccessible = true }.get(decoder) as android.media.MediaCodec
        codec.release()
        val next = TestH264Source.encode(frames = 3).first { !it.isConfig && !it.isKey }
        decoder.decode(next.data, 0, next.data.size)

        assertEquals("said once, for the one picture ($log)", 1, lost.count { "failed on a picture" in it })
    }

    private companion object {
        const val FILLER_BUDGET_MS = 12_000L
    }
}
