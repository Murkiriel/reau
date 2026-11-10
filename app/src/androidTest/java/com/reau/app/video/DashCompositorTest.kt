package com.reau.app.video

import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import com.reau.app.dash.video.EglPainter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The compositor between a synthetic "Android Auto" picture ([TestPattern], drawn on its input surface) and the
 * real dash encoder. What comes out of the encoder is decoded again and looked at: the colours have to be where
 * the mapping says. No Android Auto and no dash here.
 */
@RunWith(AndroidJUnit4::class)
class DashCompositorTest {

    /** One buffer from the encoder. */
    private class Out(val data: ByteArray, val isKey: Boolean, val ptsMs: Long) {
        /** A coded picture, as opposed to the SPS/PPS buffer. */
        val isFrame: Boolean get() = nalTypes(data).any { it == 1 || it == 5 }
    }

    private val whole = RectPx(0, 0, TestPattern.WIDTH, TestPattern.HEIGHT)
    private val log = CopyOnWriteArrayList<String>()
    private val out = CopyOnWriteArrayList<Out>()

    /**
     * The stamp of every frame the compositor handed to the encoder (its `onFrameSubmitted`). The clock tests count
     * these, not the encoder's output: the emulator's only AVC encoder is software, takes 50 to 85 ms a frame and
     * sometimes more than 150 ms to give out its first one, so counting its output measured the encoder, not the
     * compositor's pace. The pictures are still checked on what came out.
     */
    private val submitted = CopyOnWriteArrayList<Long>()
    private var compositor: DashCompositor? = null
    private val encoders = mutableListOf<DashEncoder>()

    @After
    fun tearDown() {
        compositor?.release()
        encoders.forEach { it.release() }
    }

    private fun newCompositor(): DashCompositor {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        assertNotNull(c.inputSurface)
        return c
    }

    private fun newEncoder(sink: MutableList<Out> = out): DashEncoder {
        val enc = DashEncoder { data, isKey, ptsMs -> sink += Out(data, isKey, ptsMs) }.also { encoders += it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        return enc
    }

    /** A compositor with a picture already posted, feeding a fresh encoder. */
    private fun running(pace: DashCompositor.Pace = PACE, mapping: FrameFit.Mapping? = null, picture: Boolean = true): DashCompositor {
        val c = newCompositor()
        c.setPace(pace)
        if (mapping != null) assertTrue("mapping refused: $log", c.setMapping(mapping))
        if (picture) {
            TestPattern.drawOn(checkNotNull(c.inputSurface))
            awaitSource(c, 1)
        }
        val enc = newEncoder()
        c.setOutput(checkNotNull(enc.inputSurface)) { pts -> submitted += pts; enc.frameSubmitted(pts) }
        return c
    }

    /** Waits until [condition] holds, for up to [timeoutMs]: for what the encoder gives out, which takes its time. */
    private fun awaitTrue(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }

    private var sourceSeen = 0

    /** Waits until the compositor has latched [count] more pictures. */
    private fun awaitSource(c: DashCompositor, count: Int) {
        val target = sourceSeen + count
        val deadline = System.currentTimeMillis() + 2_000
        while (sourceSeen < target && System.currentTimeMillis() < deadline) {
            sourceSeen += c.takeStats().sourceFrames
            if (sourceSeen < target) Thread.sleep(10)
        }
        assertTrue("the compositor latched $sourceSeen of $target pictures", sourceSeen >= target)
    }

    private fun frames(sink: List<Out> = out) = sink.filter { it.isFrame }

    /** Stops the output and decodes everything the encoder produced. */
    private fun stopAndDecode(c: DashCompositor): List<H264TestDecoder.Picture> {
        Thread.sleep(SETTLE_MS)
        c.clearOutput()
        encoders.forEach { it.release() }
        val pictures = H264TestDecoder().decode(out.map { H264TestDecoder.Chunk(it.data, it.ptsMs) })
        assertTrue("nothing was decoded from ${out.size} buffers", pictures.isNotEmpty())
        assertEquals(DashGlass.FRAME_W, pictures.last().width)
        assertEquals(DashGlass.FRAME_H, pictures.last().height)
        return pictures
    }

    // ── the picture is where the mapping says ──────────────────────────────────────────────────────────────

    @Test
    fun outOfTheBoxTheWholeFrameFillsTheDome() {
        val c = running()
        assertShows(stopAndDecode(c).last(), FrameFit.fillDome(whole))
    }

    @Test
    fun theWholeFrameCanBeShownSmallEnoughToBeSeenWhole() {
        val mapping = FrameFit.wholeVisible(whole)
        val c = running(mapping = mapping)
        assertShows(stopAndDecode(c).last(), mapping)
    }

    /**
     * A window from the middle, enlarged. Since the glass was measured a crop fills the whole frame, which leaves no
     * black around it to check; the compositor is tested here with a smaller destination, as a mapping can have.
     */
    @Test
    fun aWindowFromTheMiddleIsShownEnlarged() {
        val mapping = FrameFit.Mapping(RectPx(150, 85, 500, 310), RectPx(113, 0, 300, 186))
        val c = running(mapping = mapping)
        assertShows(stopAndDecode(c).last(), mapping)
    }

    /**
     * The compositor tells how long it has been inside a frame ([DashCompositor.busyMs]), which the Android Auto decoder reads before it
     * is called stalled. Between frames it is 0; while one is drawn it is above 0: a poll from another thread, thousands of times over a
     * second of moving pictures, must see it above 0 at least once. (A draw on the emulator takes several milliseconds.)
     */
    @Test
    fun theCompositorSaysItIsBusyWhileItDrawsAFrameAndZeroBetweenThem() {
        val c = running()
        assertEquals("between frames, before the first one", 0L, c.busyMs())
        val surface = checkNotNull(c.inputSurface)
        val until = SystemClock.elapsedRealtime() + 1_500
        val source = thread {
            var i = 0
            while (SystemClock.elapsedRealtime() < until) {
                runCatching { TestPattern.drawOn(surface, tint = if (i++ % 2 == 0) Color.RED else Color.BLUE) }
                Thread.sleep(15)
            }
        }
        var most = 0L
        var polls = 0
        while (SystemClock.elapsedRealtime() < until) {
            most = maxOf(most, c.busyMs())
            polls++
        }
        source.join()
        assertTrue("busyMs never rose above 0 in $polls polls: the frame is not bracketed", most > 0L)
        assertTrue("and it is not a figure that only grows (the longest frame took $most ms)", most < 1_000L)
        assertTrue("log: $log", awaitTrue { c.busyMs() == 0L })
    }

    /** One source pixel per dash pixel, taken from the top left corner: catches a flipped or mirrored window. */
    @Test
    fun aWindowFromACornerShowsThatCorner() {
        val mapping = FrameFit.Mapping(RectPx(0, 0, 300, 186), RectPx(113, 0, 300, 186))
        assertEquals(1.0, mapping.scale, 1e-9)
        val c = running(mapping = mapping)
        assertShows(stopAndDecode(c).last(), mapping)
    }

    @Test
    fun aCropFillsTheWholeFrame() {
        val mapping = FrameFit.centerCrop(whole, scalePercent = 80)
        assertEquals(RectPx(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H), mapping.dest)
        val c = running(mapping = mapping)
        assertTrue("the whole frame comes out of the encoder", stopAndDecode(c).isNotEmpty())
    }

    /**
     * The crop's window past the picture's top right corner, at 200% and -100%. What falls
     * outside Android Auto's picture reaches the dash black, never the edge's pixels smeared.
     */
    @Test
    fun aWindowPastThePicturesEdgeShowsBlackWhereThePictureEnds() {
        val mapping = FrameFit.centerCrop(whole, scalePercent = 80, centerXPercent = 200, centerYPercent = -100)
        assertTrue("past the right edge: ${mapping.source}", mapping.source.right > whole.right && mapping.source.y < 0)
        val c = running(mapping = mapping)
        assertShows(stopAndDecode(c).last(), mapping)
    }

    /** From a large destination to a small one: what the first left around the second must be black again. */
    @Test
    fun theMappingCanChangeWhileRunning() {
        val c = running(mapping = FrameFit.centerCrop(whole, scalePercent = 60, centerXPercent = 100, centerYPercent = 100))
        Thread.sleep(SETTLE_MS)
        val mapping = FrameFit.wholeVisible(whole)
        assertTrue(c.setMapping(mapping))
        assertShows(stopAndDecode(c).last(), mapping)
    }

    @Test
    fun aNewSourceSizeTakesMappingsOfThatSize() {
        // The resolution applies without switching off; Android Auto's new session
        // sends 1280×720 and the compositor, started at 800×480, must take mappings for it.
        val c = running()
        val big = RectPx(0, 0, 1280, 720)
        assertFalse("a 1280×720 window does not fit an 800×480 source", c.setMapping(FrameFit.fillDome(big)))
        c.setSource(1280, 720)
        assertTrue("it does once the source is 1280×720: $log", c.setMapping(FrameFit.fillDome(big)))
    }

    @Test
    fun aMappingThatWouldPutGarbageOnTheDashIsRefusedAndThePreviousOneStays() {
        val c = running()
        assertFalse("a source outside the frame", c.setMapping(FrameFit.Mapping(RectPx(400, 0, 480, 300), DashGlass.DOME_BOX)))
        assertFalse("a stretched picture", c.setMapping(FrameFit.Mapping(whole, RectPx(113, 0, 300, 100))))
        assertTrue("the refusal must be logged, got $log", log.any { "refused" in it })
        assertShows(stopAndDecode(c).last(), FrameFit.fillDome(whole))
    }

    // ── the clock ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun beforeAnyPictureArrivesTheDashGetsBlackAtTheIdlePace() {
        val c = running(picture = false)
        Thread.sleep(150)
        assertEquals("one frame must go out as soon as there is an output, not an idle interval later", 1, submitted.size)
        // The pace is in the stamps (the clock counts ticks, not wall time): wait for five frames, however slow the
        // emulator is, and check they are one idle interval apart.
        assertTrue("only ${submitted.size} black frames in 4 s", awaitTrue(4_000) { submitted.size >= 5 })
        assertEquals("black frames at the idle pace, got $submitted", setOf(248L), submitted.take(5).zipWithNext { a, b -> b - a }.toSet())

        val picture = stopAndDecode(c).last()
        for (py in 5 until DashGlass.FRAME_H step 20) {
            for (px in 5 until DashGlass.FRAME_W step 20) assertEquals("pixel ($px, $py)", "black", name(picture.colorAt(px, py)))
        }
    }

    @Test
    fun withTheSourceStoppedTheLastPictureKeepsGoingOutAtTheIdlePace() {
        val c = running()
        // The pace is in the stamps; wait for twelve frames (3 s on time) however slow the emulator is.
        assertTrue("only ${submitted.size} frames in 6 s", awaitTrue(6_000) { submitted.size >= 12 })
        val times = submitted.toList()
        val stats = c.takeStats()

        // A tick every 62 ms; the one nearest to 250 ms is the fourth: a frame every 248 ms, about 12 in 3 s.
        assertTrue("expected about 12 frames, got ${times.size}: $times", times.size in 12..14)
        assertEquals("the first frame of the video clock", 0L, times.first())
        assertEquals("every repeat is four ticks after the frame before, got $times", setOf(248L), times.zipWithNext { a, b -> b - a }.toSet())
        assertEquals("one picture arrived, so one frame was drawn from a new picture", 1, stats.drawn)
        assertTrue("the rest were repeats, got $stats", stats.repeated >= 9)
        assertTrue("composing must have been timed, got $stats", stats.composeNs > 0)
        c.clearOutput()
        var encoded = 0
        awaitTrue { encoded += encoders.first().collectOutput().frames; encoded >= times.size }
        assertEquals("the encoder must have been told of every frame it got, so it can time them", times.size, encoded)

        assertShows(stopAndDecode(c).last(), FrameFit.fillDome(whole))
    }

    @Test
    fun aSourceThatKeepsChangingComesOutAtTheActivePace() {
        val c = running()
        val surface = checkNotNull(c.inputSurface)
        val tints = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val until = System.currentTimeMillis() + 2_000
        var posted = 0
        while (System.currentTimeMillis() < until) {
            TestPattern.drawOn(surface, tint = tints[posted++ % tints.size])
            Thread.sleep(20)
        }
        Thread.sleep(100)
        val times = submitted.toList()
        val steps = times.zipWithNext { a, b -> b - a }

        // 2 s at a tick every 62 ms is 32 frames; the source posted more pictures than that.
        // A precondition, not the behaviour under test: on a worn-down emulator the source itself cannot post 40 pictures
        // in 2 s, and then the test is skipped, saying so, not failed.
        assumeTrue("the source must be faster than the output for this test to mean anything, posted $posted (skipped, not passed)", posted >= 40)
        assertTrue("expected about 32 frames in 2 s, got ${times.size}", times.size in 26..36)
        assertTrue("every frame is a whole number of ticks after the one before, got $steps", steps.all { it > 0 && it % 62 == 0L })
        assertTrue("most frames are one tick apart, got $steps", steps.count { it == 62L } >= steps.size * 8 / 10)
    }

    /** A changing source for [ms]: a picture every 20 ms. Returns how many were posted. */
    private fun keepPosting(c: DashCompositor, ms: Long): Int {
        val surface = checkNotNull(c.inputSurface)
        val tints = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val until = System.currentTimeMillis() + ms
        var posted = 0
        while (System.currentTimeMillis() < until) {
            TestPattern.drawOn(surface, tint = tints[posted++ % tints.size])
            Thread.sleep(20)
        }
        return posted
    }

    @Test
    fun aPhonePreviewGetsThePictureWithoutChangingTheDashsPace() {
        // A screen to see Android Auto running in the background. The preview is drawn
        // after the dash's frame is out, without waiting for the phone's vsync; the dash keeps its pace.
        val c = running()
        val thread = android.os.HandlerThread("preview-reader").apply { start() }
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 3)
        val previewFrames = java.util.concurrent.atomic.AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            previewFrames.incrementAndGet()
        }, android.os.Handler(thread.looper))
        try {
            c.setPreview(reader.surface, 400, 240)
            val start = submitted.size
            val posted = keepPosting(c, 2_000)
            Thread.sleep(100)
            val toDash = submitted.size - start
            assumeTrue("the source must be faster than the output for this test to mean anything, posted $posted (skipped, not passed)", posted >= 40)
            assertTrue("the preview got ${previewFrames.get()} pictures in 2 s", previewFrames.get() >= 10)
            assertTrue("the dash keeps its pace with the preview on: about 32 frames in 2 s, got $toDash", toDash in 26..36)

            c.clearPreview()
            Thread.sleep(200)
            val afterClear = previewFrames.get()
            val dashBefore = submitted.size
            keepPosting(c, 500)
            assertTrue("no more pictures once the preview is cleared", previewFrames.get() - afterClear <= 1)
            assertTrue("the dash carries on after the preview is cleared", submitted.size - dashBefore >= 5)
        } finally {
            reader.close()
            thread.quitSafely()
        }
    }

    @Test
    fun withThePhonePreviewOnAFitChangedInTheSettingsReachesTheDashAtOnce() {
        // Every change to the crop must happen in real time, with the preview on. The preview takes the GL context
        // after each dash frame; the next frame must still go to the dash, with the new fit, even with Android Auto's
        // picture standing still (then the change goes out with the next repeat, a quarter of a second at most).
        // Starts from a crop used on a real dash.
        val c = running(mapping = FrameFit.centerCrop(whole, scalePercent = 68, centerXPercent = 81, centerYPercent = 96))
        val thread = android.os.HandlerThread("preview-fit").apply { start() }
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, android.os.Handler(thread.looper))
        try {
            c.setPreview(reader.surface, 400, 240)
            // The plain pattern again (PictureCheck reads its colours), then a few dash frames with the preview drawn.
            TestPattern.drawOn(checkNotNull(c.inputSurface))
            awaitSource(c, 1)
            Thread.sleep(400)
            val moved = FrameFit.moved(FrameFit.wholeVisible(whole), -40, -30)
            assertTrue(c.setMapping(moved))
            assertShows(stopAndDecode(c).last(), moved)
        } finally {
            reader.close()
            thread.quitSafely()
        }
    }

    @Test
    fun aPreviewSurfaceThatGoesAwayNeverStopsTheDash() {
        val c = running()
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 2)
        c.setPreview(reader.surface, 400, 240)
        keepPosting(c, 300)
        reader.close() // the screen went away without saying so
        val before = submitted.size
        keepPosting(c, 1_000)
        assertTrue("the dash kept getting frames with the preview surface gone: ${submitted.size - before}", submitted.size - before >= 10)
    }

    @Test
    fun withNoDashYetThePhonePreviewStillShowsAndroidAuto() {
        // Android Auto up and the dash not connected yet, the phone still sees (and
        // touches) Android Auto. With no output the compositor draws the preview at each new picture.
        val c = newCompositor() // no setOutput: no dash
        TestPattern.drawOn(checkNotNull(c.inputSurface))
        awaitSource(c, 1)
        val thread = android.os.HandlerThread("preview-no-dash").apply { start() }
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 3)
        val previewFrames = java.util.concurrent.atomic.AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            previewFrames.incrementAndGet()
        }, android.os.Handler(thread.looper))
        try {
            c.setPreview(reader.surface, 400, 240)
            assertTrue("the picture already there shows at once", awaitTrue { previewFrames.get() >= 1 })
            keepPosting(c, 1_000)
            assertTrue("new pictures reach the preview with no dash: ${previewFrames.get()}", previewFrames.get() >= 10)
            assertTrue("nothing went to a dash", submitted.isEmpty())
        } finally {
            reader.close()
            thread.quitSafely()
        }
    }

    @Test
    fun aPreviewSurfaceAlreadyGoneIsRefusedAndTheDashGoesOn() {
        // Found by the full suite: the screen closed before the compositor took its surface, and the
        // surface's creation threw IllegalArgumentException on the GL thread, which crashed the app.
        val c = running()
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 2)
        val gone = reader.surface
        reader.close()
        c.setPreview(gone, 400, 240)
        val before = submitted.size
        keepPosting(c, 1_000)
        assertTrue("the dash kept getting frames: ${submitted.size - before}", submitted.size - before >= 5) // goes on, at whatever pace the (slow) emulator gives
        assertTrue("the refusal is in the log: $log", log.any { "phone preview refused" in it })
    }

    @Test
    fun theVideoClockCanRunSlow() {
        val c = running(pace = DashCompositor.Pace(activeMs = 62, idleMs = 250, clockSlowPercent = 5))
        assertTrue("only ${submitted.size} frames in 4 s", awaitTrue(4_000) { submitted.size >= 5 })
        val steps = submitted.zipWithNext { a, b -> b - a }

        // 248 ms of real time between repeats, stamped 5% short: 235.6 ms.
        assertTrue("expected at least 4 repeats, got $steps", steps.size >= 4)
        assertTrue("every step must be 235 or 236 ms, got $steps", steps.all { it == 235L || it == 236L })
        c.clearOutput()
    }

    // ── outputs come and go ────────────────────────────────────────────────────────────────────────────────

    /**
     * With no output yet (the dash not connected), whoever feeds the input must not be held back, and when the
     * output comes the picture shown is the most recent one. How many of the pictures in between the compositor
     * gets to see is not its business: this producer's queue drops the ones nobody took in time.
     */
    @Test
    fun picturesPostedWithNoOutputNeitherBlockThePosterNorGetLost() {
        val c = newCompositor()
        val surface = checkNotNull(c.inputSurface)
        val tints = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        // More pictures than a buffer queue holds: if nobody took them, a post would block here for good.
        val posted = CountDownLatch(1)
        thread {
            repeat(12) { TestPattern.drawOn(surface, tint = tints[it % tints.size]) }
            posted.countDown()
        }
        assertTrue("posting pictures blocked with no output", posted.await(5, TimeUnit.SECONDS))
        awaitSource(c, 1)
        assertEquals("nothing is drawn without an output", 0, c.takeStats().let { it.drawn + it.repeated })

        val enc = newEncoder()
        c.setOutput(checkNotNull(enc.inputSurface), onFrameSubmitted = enc::frameSubmitted)
        val picture = stopAndDecode(c).last()
        val d = FrameFit.fillDome(whole).dest
        // The twelfth picture was all yellow.
        for (px in d.x + 20 until d.right - 20 step 40) {
            for (py in d.y + 20 until d.bottom - 20 step 40) assertEquals("pixel ($px, $py)", "yellow", name(picture.colorAt(px, py)))
        }
    }

    /** A rate change: the new encoder gets the output, then the old one is released. */
    @Test
    fun theVideoClockCarriesOnWhenTheOutputMovesToANewEncoder() {
        val c = running()
        Thread.sleep(SETTLE_MS)
        val fromNew = CopyOnWriteArrayList<Out>()
        val toNew = CopyOnWriteArrayList<Long>()
        val fresh = newEncoder(fromNew)
        val lastOfOld = submitted.last()
        c.setOutput(checkNotNull(fresh.inputSurface)) { pts -> toNew += pts; fresh.frameSubmitted(pts) }
        val toOldAfterSwitch = submitted.size
        encoders.first().release()
        val countOfOld = out.size
        // Five frames on the new encoder (about 1 s on time), however slow the emulator is.
        assertTrue("only ${toNew.size} frames on the new encoder in 4 s", awaitTrue(4_000) { toNew.size >= 5 })

        assertEquals("nothing more handed to the old encoder once setOutput returned", toOldAfterSwitch, submitted.size)
        assertEquals("nothing more out of the old encoder once it was released", countOfOld, out.size)
        val timesOfNew = toNew.toList()
        // One at once, then one every 248 ms. A second clock left running would put frames between them.
        assertEquals("the new encoder must get frames at the idle pace, got $timesOfNew", setOf(248L), timesOfNew.zipWithNext { x, y -> y - x }.toSet())
        assertTrue("the clock must carry on: old ended at $lastOfOld, new has $timesOfNew", timesOfNew.first() > lastOfOld)
        assertTrue("the new encoder gave nothing out", awaitTrue { frames(fromNew).isNotEmpty() })
        assertTrue("the new encoder opens with a keyframe", frames(fromNew).first().isKey)
        c.clearOutput()
    }

    @Test
    fun clearingTheOutputStopsTheClock() {
        val c = running()
        Thread.sleep(SETTLE_MS)
        c.clearOutput()
        c.takeStats()
        Thread.sleep(SETTLE_MS)
        assertEquals("no frame may be drawn after clearOutput()", 0, c.takeStats().let { it.drawn + it.repeated })
    }

    /** A surface takes one producer at a time: while the compositor holds it, nobody else can draw on it. */
    @Test
    fun clearingTheOutputFreesTheEncoderSurfaceForAnotherProducer() {
        val c = running()
        Thread.sleep(SETTLE_MS)
        val surface = checkNotNull(encoders.first().inputSurface)
        assertTrue("while the compositor has the surface a second producer must be refused", runCatching { EglPainter(surface) }.isFailure)

        c.clearOutput()
        val painter = runCatching { EglPainter(surface) }
        assertTrue("the surface is still taken after clearOutput(): ${painter.exceptionOrNull()}", painter.isSuccess)
        try {
            painter.getOrThrow().paint(Color.GREEN, ptsMs = 1_000_000)
            // By its stamp: frames the compositor posted before clearOutput() may still be coming out of the encoder.
            assertTrue("the other producer's frame must reach the encoder", awaitTrue { frames().any { it.ptsMs == 1_000_000L } })
        } finally {
            painter.getOrThrow().release()
        }
    }

    // ── the rider's input: the answer is drawn at once ─────────────────────────────────────────────────────

    /** A slow compositor (a tick a second) just after a tick, with a new picture: the time of each frame the encoder was handed. */
    private fun slowAfterATick(gapMs: Long, inputFresh: Boolean): List<Long> {
        val c = newCompositor()
        c.setPace(DashCompositor.Pace(activeMs = 1_000, idleMs = 1_000))
        TestPattern.drawOn(checkNotNull(c.inputSurface))
        awaitSource(c, 1)
        val at = CopyOnWriteArrayList<Long>()
        val enc = newEncoder()
        c.setOutput(checkNotNull(enc.inputSurface)) { pts -> at += SystemClock.uptimeMillis(); enc.frameSubmitted(pts) }
        assertTrue("the first two frames (a tick apart)", awaitTrue(4_000) { at.size >= 2 })
        c.burstGapMs = gapMs
        if (inputFresh) com.reau.app.stream.InputBurst.sent(SystemClock.uptimeMillis())
        TestPattern.drawOn(checkNotNull(c.inputSurface), tint = Color.MAGENTA)
        Thread.sleep(500)
        val seen = at.toList()
        c.release()
        compositor = null
        return seen
    }

    @Test
    fun aNewPictureRightAfterTheRidersInputIsDrawnAtOnceNotAtTheNextTick() {
        val at = slowAfterATick(gapMs = 80, inputFresh = true)
        assertTrue("a third frame within 500 ms, not a second away: $at", at.size >= 3)
        assertTrue("and soon after the second: $at", at[2] - at[1] < 400)
    }

    @Test
    fun withTheBurstOffOrNoInputTheNewPictureWaitsForTheTick() {
        assertEquals("burst off", 2, slowAfterATick(gapMs = 0, inputFresh = true).size)
        // The input of the test above is still fresh (1.8 s): the second case runs later than that
        Thread.sleep(2_000)
        assertEquals("no fresh input", 2, slowAfterATick(gapMs = 80, inputFresh = false).size)
    }

    // ── the rider's black bands and the zoom ──────────────────────────────────────────────────────────────────

    /** Bands over the picture: black where they are, the picture everywhere else (checked pixel by pixel in PictureCheck). */
    @Test
    fun theBlackBandsCoverThePictureAtTheFramesEdges() {
        val mapping = FrameFit.fillDome(whole).copy(cover = FrameFit.Cover(top = 80, bottom = 30, left = 120, right = 60))
        val c = running(mapping = mapping)
        val picture = stopAndDecode(c).last()
        assertShows(picture, mapping)
        assertEquals("the top band", "black", name(picture.colorAt(300, 20)))
        assertEquals("the left band", "black", name(picture.colorAt(40, 150)))
        assertEquals("the right band", "black", name(picture.colorAt(DashGlass.FRAME_W - 20, 150)))
        assertEquals("the bottom band", "black", name(picture.colorAt(300, DashGlass.FRAME_H - 10)))
    }

    @Test
    fun theBandsCanChangeWhileRunning() {
        val c = running(mapping = FrameFit.fillDome(whole).copy(cover = FrameFit.Cover(top = 100)))
        Thread.sleep(300)
        val open = FrameFit.fillDome(whole)
        assertTrue("the new mapping was taken: $log", c.setMapping(open))
        assertShows(stopAndDecode(c).last(), open)
    }

    /** Zoomed out the picture is smaller and black around it; zoomed in it is nearer (the source window smaller). */
    @Test
    fun theZoomIsDrawnAsASmallerOrANearerPicture() {
        val farther = FrameFit.zoomed(FrameFit.wholeVisible(whole), 60)
        val c = running(mapping = farther)
        assertShows(stopAndDecode(c).last(), farther)
    }

    @Test
    fun aNearerPictureShowsTheBottomOfTheSource() {
        val nearer = FrameFit.zoomed(FrameFit.fillDome(whole), 150)
        val c = running(mapping = nearer)
        assertShows(stopAndDecode(c).last(), nearer)
    }

    /**
     * The picture's look is told about every half second, with the rows the right way up and reduced by the average of each block (what [PictureLook]'s net was
     * trained on): a picture dark in its top half and one dark in its bottom half are two different screens to it (the first a map, the second nothing sure: see
     * `PictureLookTest`), and swap if the copy comes upside down; a black one is unsure.
     */
    @Test
    fun theLookReadsThePictureTheRightWayUp() {
        val c = newCompositor()
        val looks = CopyOnWriteArrayList<PictureLook.Kind>()
        c.onLook = { looks += it }
        val surface = checkNotNull(c.inputSurface)
        // The picture the net reads as a map when it stands as it is (dark over white, `PictureLookTest`'s "dark over white", 0.93) and as nothing sure when it is
        // turned over (white over dark, 0.72).
        fun gradient(turned: Boolean): android.graphics.Bitmap {
            val pixels = IntArray(800 * 480) { i -> if (((i / 800) < 240) != turned) Color.BLACK else Color.WHITE }
            return android.graphics.Bitmap.createBitmap(pixels, 800, 480, android.graphics.Bitmap.Config.ARGB_8888)
        }
        fun show(picture: android.graphics.Bitmap?) {
            Thread.sleep(350)      // the compositor looks at most every 250 ms: a picture that comes sooner after the last look is not looked at
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.BLACK)
            if (picture != null) canvas.drawBitmap(picture, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }
        // the first look builds the filter program and the small framebuffer: seconds on the emulator's software GL
        show(gradient(turned = false))
        assertTrue("the picture as it is: a map, got $looks; log $log", awaitTrue(timeoutMs = 20_000) { looks.lastOrNull() == PictureLook.Kind.MAP })
        show(gradient(turned = true))
        assertTrue("turned over it is not the map, got $looks; log $log", awaitTrue { looks.lastOrNull().let { it != null && it != PictureLook.Kind.MAP } })
        show(null)
        assertTrue("a black picture is unsure, got $looks; log $log", awaitTrue { looks.lastOrNull() == PictureLook.Kind.UNSURE })
    }

    @Test
    fun aPictureThatStaysStillIsLookedAtAgainAndAgain() {
        val c = newCompositor()
        val looks = CopyOnWriteArrayList<PictureLook.Kind>()
        c.onLook = { looks += it }
        val surface = checkNotNull(c.inputSurface)
        val canvas = surface.lockCanvas(null)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height / 2f, android.graphics.Paint().apply { color = Color.rgb(200, 200, 200) })
        surface.unlockCanvasAndPost(canvas)
        // one picture only: the screen needs three looks of the same in a row, so they must keep coming without a new one
        assertTrue("a still picture is looked at again, got $looks", awaitTrue(timeoutMs = 20_000) { looks.size >= 3 })
        // what the net says of this synthetic picture is its own business (the retrained one is unsure of it); a still picture says the same every time
        assertEquals("always the same: $looks", 1, looks.toSet().size)
    }

    private fun assertShows(picture: H264TestDecoder.Picture, mapping: FrameFit.Mapping) = PictureCheck.assertShows(picture, mapping)

    private companion object {
        val PACE = DashCompositor.Pace(activeMs = 62, idleMs = 250)
        const val SETTLE_MS = 700L

        fun name(color: Int): String = PictureCheck.name(color)

        /** NAL unit types of an Annex-B buffer, in order. */
        fun nalTypes(data: ByteArray): List<Int> = (0..data.size - 4)
            .filter { data[it].toInt() == 0 && data[it + 1].toInt() == 0 && data[it + 2].toInt() == 1 }
            .map { data[it + 3].toInt() and 0x1F }
    }
}
