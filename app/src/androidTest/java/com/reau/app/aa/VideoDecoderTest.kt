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
    private fun feedFillerUntilTheDecoderGivesUp(): String {
        val filler = byteArrayOf(0, 0, 0, 1, 0x0C, 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte())
        val start = System.currentTimeMillis()
        var last = start
        var count = 0
        var longestGap = 0L
        while (decoderErrors.get() == 0 && System.currentTimeMillis() - start < FILLER_BUDGET_MS) {
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

    private companion object {
        const val FILLER_BUDGET_MS = 12_000L
    }
}
