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
 * thermal status at the start of a stream, and the stream runs at it. Real compositor and encoder, a collector for the dash.
 * (The test of a rate that came down while streaming left with the fps ladder, 14/11/2026.)
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
        startLoop(VideoLoop.Settings(ceilingFps = 16))
        awaitLog("start at 16", EVENT_MS) { "video loop start: 16 fps (ceiling 16)" in it }
    }

    @Test
    fun aSeverelyHotPhoneStartsUnderTheHotCeiling() {
        status = 3 // SEVERE
        startLoop(VideoLoop.Settings(ceilingFps = 16))
        awaitLog("start at 10", EVENT_MS) { "video loop start: 10 fps (ceiling 10)" in it }
        awaitLog("the reason in the log", EVENT_MS) { "thermal severe: ceiling 10 of 16" in it }
    }

    private companion object {
        // A deadline for things that must happen, not a duration: how soon depends on how busy the machine is.
        const val EVENT_MS = 10_000L
    }
}
