package com.reau.app.stream

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.video.DashCompositor
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The video loop on a hot phone: the frame ceiling follows [ThermalCeiling] for the phone's
 * thermal status, at the start of a stream and while it runs. Real compositor and encoder, a collector for the dash.
 */
@RunWith(AndroidJUnit4::class)
class VideoLoopThermalTest {
    private class Collector : VideoLoop.Dash {
        val packets = CopyOnWriteArrayList<ByteArray>()
        override val firstFrameConfirmed = true
        override val decodedFrameCount = 0L
        override fun sendRtp(packet: ByteArray) {
            packets += packet
        }
    }

    private val dash = Collector()
    private val log = CopyOnWriteArrayList<String>()
    private var compositor: DashCompositor? = null
    private var loop: VideoLoop? = null
    private val sourceRunning = AtomicBoolean(false)
    private var source: Thread? = null
    @Volatile private var status = 0

    @After
    fun tearDown() {
        sourceRunning.set(false)
        source?.join(1_000)
        loop?.stop()
        compositor?.release()
    }

    private fun startLoop(settings: VideoLoop.Settings): VideoLoop {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        val surface = checkNotNull(c.inputSurface)
        sourceRunning.set(true)
        source = thread {
            var i = 0
            val tints = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            while (sourceRunning.get()) {
                runCatching { TestPattern.drawOn(surface, tint = tints[i++ % tints.size]) }
                Thread.sleep(20)
            }
        }
        Thread.sleep(100)
        return VideoLoop(c, dash, settings, thermalStatus = { status }, log = { log += it }).also {
            loop = it
            assertTrue(it.start())
        }
    }

    private fun awaitLog(what: String, timeoutMs: Long, match: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (log.none(match) && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue("$what: ${log.filter { "video" in it || "thermal" in it || "rebuilt" in it }}", log.any(match))
    }

    @Test
    fun aCoolPhoneStartsAtTheRidersCeiling() {
        startLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16))
        awaitLog("start at 16", EVENT_MS) { "video loop start: 16 fps (ceiling 16)" in it }
    }

    @Test
    fun aSeverelyHotPhoneStartsUnderTheHotCeiling() {
        status = 3 // SEVERE
        startLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16))
        awaitLog("start at 10", EVENT_MS) { "video loop start: 10 fps (ceiling 10)" in it }
        awaitLog("the reason in the log", EVENT_MS) { "thermal severe: ceiling 10 of 16" in it }
    }

    @Test
    fun aPhoneThatHeatsUpWhileStreamingComesDownToTheHotCeiling() {
        EncoderCapacity.assumeKeepsUp(16)
        startLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16))
        Thread.sleep(2_000)
        status = 2 // MODERATE: 12 of 16
        awaitLog("the change seen", EVENT_MS) { "thermal moderate: ceiling 12 of 16" in it }
        awaitLog("windows judged hot", WINDOW_MS) { it.startsWith("video fps target=16") && "thermal=HOT" in it }
        awaitLog("down to 12", REBUILD_MS) { "encoder rebuilt: 12 fps" in it }
    }

    private companion object {
        // Deadlines for things that must happen, not durations: how soon depends on how busy the machine is.
        // A poll is every 250 ms and a measurement window 5 s; a window only counts when the picture really moved
        // and the compositor delivered, so a few of them may be left out before the rate comes down.
        const val EVENT_MS = 10_000L
        const val WINDOW_MS = 30_000L
        const val REBUILD_MS = 90_000L
    }
}
