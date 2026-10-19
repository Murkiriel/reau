package com.reau.app.stream

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.video.DashCompositor
import com.reau.app.video.DashGlass
import com.reau.app.video.FrameFit
import com.reau.app.video.H264TestDecoder
import com.reau.app.video.PictureCheck
import com.reau.app.video.RectPx
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The debug recording of the video sent to the dash, end to end on the emulator: the loop records, the file is read
 * back from the app's external files folder and decoded, and the picture in it is the one that was put in.
 */
@RunWith(AndroidJUnit4::class)
class VideoRecordingTest {

    private class Sink : VideoLoop.Dash {
        val packets = CopyOnWriteArrayList<ByteArray>()
        override val firstFrameConfirmed = true
        override val decodedFrameCount = 0L
        override fun sendRtp(packet: ByteArray) {
            packets += packet
        }
    }

    private val dash = Sink()
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var dir: File
    private var compositor: DashCompositor? = null
    private var loop: VideoLoop? = null

    @Before
    fun cleanFolder() {
        val external = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        dir = File(external, "video")
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        loop?.stop()
        compositor?.release()
        dir.deleteRecursively()
    }

    private fun newLoop(settings: VideoLoop.Settings): VideoLoop {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        TestPattern.drawOn(checkNotNull(c.inputSurface))
        Thread.sleep(100)
        return VideoLoop(c, dash, settings, log = { log += it }).also { loop = it }
    }

    /** Frames sent so far (one per marker bit). */
    private fun framesSent() = dash.packets.count { it[1].toInt() and 0x80 != 0 }

    /** Waits for [condition] (a deadline that only says "it never happened": how long it takes depends on the machine). */
    private fun awaitThat(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue("$what: not within $timeoutMs ms; ${framesSent()} frames sent, log $log", condition())
    }

    private fun recordings() = dir.listFiles { f -> f.extension == "h264" }.orEmpty().sortedBy { it.name }

    /** The units of an Annex-B file, each with its start code, as the recorder wrote them. */
    private fun unitsOf(file: File, sizes: List<Int>): List<ByteArray> {
        val data = file.readBytes()
        var at = 0
        return sizes.map { size -> data.copyOfRange(at, at + 4 + size).also { at += 4 + size } }.also {
            assertEquals("the index must account for every byte of the file", data.size, at)
        }
    }

    @Test
    fun theRecordedFileIsReadBackAndDecodesToThePictureThatWasSent() {
        val l = newLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16, recordFrames = 8, recordDir = dir))
        assertTrue(l.start())
        // A still picture: a frame every 248 ms once the pace is in force, so 8 units take about 2 s.
        awaitThat("the recording of 8 units and 3 more frames after it") { log.any { "video recording complete: 8 frames" in it } && framesSent() > 10 }
        l.stop()

        val file = recordings().singleOrNull() ?: throw AssertionError("expected one recording in $dir, got ${dir.list()?.toList()}; log: $log")
        val index = File(dir, file.nameWithoutExtension + ".csv").readLines()
        assertEquals("frame,pts_ms,bytes,key", index.first())
        val rows = index.drop(1).map { it.split(",") }
        assertEquals("the limit was 8 units", 8, rows.size)
        assertEquals("the recording opens with a keyframe", "1", rows.first()[3])
        val times = rows.map { it[1].toLong() }
        assertEquals("the times in the index must be the ones stamped on the frames, got $times", times.sorted().distinct(), times)
        // The stream opens at the still pace (250 ms) and its first poll moves the compositor to a tick every 62 ms;
        // the tick that finds the new pace in force still adds the old one (a second 250 when the poll comes after
        // the tick at 250). From then on a repeat every 248 ms, exactly.
        val steps = times.zipWithNext { a, b -> b - a }
        val steady = steps.dropWhile { it == 250L }
        assertTrue("the stream opens with one or two steps of the still pace (250 ms), got $steps", steps.size - steady.size in 1..2)
        assertTrue("then a repeat every 248 ms, got $steps", steady.size >= 4 && steady.all { it == 248L })

        val units = unitsOf(file, rows.map { it[2].toInt() })
        assertTrue("every unit starts with a start code", units.all { it[0].toInt() == 0 && it[1].toInt() == 0 && it[2].toInt() == 0 && it[3].toInt() == 1 })
        val pictures = H264TestDecoder().decode(units.mapIndexed { i, unit -> H264TestDecoder.Chunk(unit, times[i]) })
        assertEquals("every recorded unit must decode to a picture", 8, pictures.size)
        assertEquals(DashGlass.FRAME_W, pictures.last().width)
        assertEquals(DashGlass.FRAME_H, pictures.last().height)
        PictureCheck.assertShows(pictures.last(), FrameFit.fillDome(RectPx(0, 0, TestPattern.WIDTH, TestPattern.HEIGHT)))

        assertTrue("the end of the recording must be logged with the path, got $log", log.any { "video recording complete: 8 frames" in it && file.path in it })
        assertTrue("the stream must have gone on past the recording's limit, sent ${framesSent()} frames", framesSent() > 8)
    }

    @Test
    fun aStreamStoppedBeforeTheLimitLeavesAReadableFile() {
        val l = newLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16, recordFrames = 500, recordDir = dir))
        assertTrue(l.start())
        awaitThat("4 frames sent") { framesSent() >= 4 }
        l.stop()

        val file = recordings().singleOrNull() ?: throw AssertionError("expected one recording in $dir, got ${dir.list()?.toList()}; log: $log")
        val rows = File(dir, file.nameWithoutExtension + ".csv").readLines().drop(1).map { it.split(",") }
        assertTrue("expected the 4 units sent before the stop or a few more, got ${rows.size}", rows.size in 3..8)
        val units = unitsOf(file, rows.map { it[2].toInt() })
        val pictures = H264TestDecoder().decode(units.mapIndexed { i, unit -> H264TestDecoder.Chunk(unit, rows[i][1].toLong()) })
        assertEquals(rows.size, pictures.size)
        assertTrue("got $log", log.any { "video recording stopped early: ${rows.size} frames" in it })
    }

    @Test
    fun withTheOptionOffNothingIsWritten() {
        val l = newLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16, recordFrames = 0, recordDir = dir))
        assertTrue(l.start())
        awaitThat("the stream running") { framesSent() >= 2 }
        l.stop()

        assertFalse("no folder may be created with the option off", dir.exists())
        assertTrue(log.none { "video recording" in it })
        assertTrue("the stream itself must have run", dash.packets.isNotEmpty())
    }

    @Test
    fun everyStreamGetsItsOwnRecording() {
        val l = newLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16, recordFrames = 2, recordDir = dir))
        assertTrue(l.start())
        awaitThat("the first stream's 2 frames") { framesSent() >= 2 }
        l.stop()
        Thread.sleep(1_100) // file names carry the second the stream began
        val first = framesSent()
        assertTrue(l.start())
        awaitThat("the second stream's 2 frames") { framesSent() >= first + 2 }
        l.stop()

        val files = recordings()
        assertEquals("two streams, two recordings, got ${files.map { it.name }}", 2, files.size)
        for (file in files) assertEquals(3, File(dir, file.nameWithoutExtension + ".csv").readLines().size)
    }
}
