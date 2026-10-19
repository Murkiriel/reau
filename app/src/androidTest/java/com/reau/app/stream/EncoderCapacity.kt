package com.reau.app.stream

import android.graphics.Color
import com.reau.app.video.DashCompositor
import com.reau.app.video.TestPattern
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue

/**
 * How long this device's AVC encoder takes per dash frame, measured once per test process by running the real loop
 * at 16 a second for a few seconds and reading the `encode=` it logs.
 *
 * The rate tests check the loop's pacing, which only shows when the encoder keeps up with the rate. The emulator's
 * only AVC encoder is software (`c2.android.avc.encoder`): it was measured at 75-82 ms a 526x300 frame, about
 * 12 a second, and the tests that need 16 failed there whatever the loop did. Where the encoder is too slow those
 * tests are skipped, saying the measured time, never passed.
 */
internal object EncoderCapacity {
    private var measuredMs: Double? = null

    /** Skips the calling test when the encoder cannot do [fps] frames a second with 20% to spare. */
    fun assumeKeepsUp(fps: Int) {
        val ms = msPerFrame()
        assumeTrue("the encoder takes ${"%.1f".format(ms)} ms a frame here, too slow for $fps a second (skipped, not passed)", ms <= 1000.0 / fps * 0.8)
    }

    @Synchronized
    fun msPerFrame(): Double = measuredMs ?: measure().also { measuredMs = it }

    private fun measure(): Double {
        val log = CopyOnWriteArrayList<String>()
        val compositor = DashCompositor { log += it }
        check(TestPattern.startCompositor(compositor, log)) { "compositor did not start: $log" }
        val surface = checkNotNull(compositor.inputSurface)
        val running = AtomicBoolean(true)
        val source = thread {
            var i = 0
            val tints = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            while (running.get()) {
                runCatching { TestPattern.drawOn(surface, tint = tints[i++ % tints.size]) }
                Thread.sleep(20)
            }
        }
        val dash = object : VideoLoop.Dash {
            override val firstFrameConfirmed = true
            override val decodedFrameCount = 0L
            override fun sendRtp(packet: ByteArray) = Unit
        }
        val loop = VideoLoop(compositor, dash, VideoLoop.Settings(startFps = 16, ceilingFps = 16), log = { log += it })
        try {
            check(loop.start()) { "loop did not start: $log" }
            val deadline = System.currentTimeMillis() + 12_000
            while (encodeTimes(log).size < 1 && System.currentTimeMillis() < deadline) Thread.sleep(200)
        } finally {
            running.set(false)
            source.join(1_000)
            loop.stop()
            compositor.release()
        }
        val times = encodeTimes(log).sorted()
        check(times.isNotEmpty()) { "no encode= in the loop's log: $log" }
        return times[times.size / 2]
    }

    private fun encodeTimes(log: List<String>): List<Double> =
        log.mapNotNull { Regex("""encode=([0-9.]+)ms""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
}
