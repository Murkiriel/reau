package com.reau.app.stream

import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.video.EncoderOutputPump
import com.reau.app.video.DashCompositor
import com.reau.app.video.TestPattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The video loop from a synthetic "Android Auto" picture to RTP packets, against a collector standing where the
 * dash session would be. Real compositor, real encoder; no network.
 */
@RunWith(AndroidJUnit4::class)
class VideoLoopTest {

    /** Stands where the dash session would be: keeps every RTP packet. */
    private class Collector : VideoLoop.Dash {
        val packets = CopyOnWriteArrayList<ByteArray>()

        /** When each packet in [packets] arrived (same index), on the wall clock. Always added first, so never shorter. */
        val arrivalsMs = CopyOnWriteArrayList<Long>()
        val failNextSend = AtomicBoolean(false)
        @Volatile override var firstFrameConfirmed = true
        override val decodedFrameCount = 0L

        override fun sendRtp(packet: ByteArray) {
            check(!failNextSend.getAndSet(false)) { "the dash socket is gone" }
            arrivalsMs += SystemClock.elapsedRealtime()
            packets += packet
        }
    }

    /** One access unit put back together from its RTP packets; [arrivalMs] is when its last packet arrived. */
    private class Unit(
        val timestamp: Long,
        val nal: ByteArray,
        val lastPacket: Int,
        val largestPacket: Int,
        val arrivalMs: Long,
    ) {
        val nalType: Int get() = nal[0].toInt() and 0x1F

        /** SPS + PPS + IDR in one unit, as the dash needs its keyframes. */
        val isKeyframeBundle: Boolean
            get() = nalType == NAL_SPS && indexOfNal(NAL_PPS) > 0 && indexOfNal(NAL_IDR) > indexOfNal(NAL_PPS)

        private fun indexOfNal(type: Int): Int = (0..nal.size - 5).firstOrNull {
            nal[it].toInt() == 0 && nal[it + 1].toInt() == 0 && nal[it + 2].toInt() == 0 && nal[it + 3].toInt() == 1 &&
                nal[it + 4].toInt() and 0x1F == type
        } ?: -1
    }

    private val dash = Collector()
    private val log = CopyOnWriteArrayList<String>()
    private var compositor: DashCompositor? = null
    private var loop: VideoLoop? = null
    private val sourceRunning = AtomicBoolean(false)
    private var source: Thread? = null

    @After
    fun tearDown() {
        stopSource()
        loop?.stop()
        compositor?.release()
    }

    /** A compositor with one picture in it and a loop on top, not started yet. */
    private fun newLoop(
        settings: VideoLoop.Settings = VideoLoop.Settings(startFps = 16, ceilingFps = 16),
        link: () -> LinkSample? = { null },
    ): VideoLoop {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        TestPattern.drawOn(checkNotNull(c.inputSurface))
        Thread.sleep(100)
        return VideoLoop(c, dash, settings, log = { log += it }, link = link).also { loop = it }
    }

    /** Posts a different picture about 50 times a second, as a moving Android Auto would. */
    private fun startSource() {
        val surface = checkNotNull(compositor?.inputSurface)
        val tints = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        sourceRunning.set(true)
        source = thread {
            var i = 0
            while (sourceRunning.get()) {
                runCatching { TestPattern.drawOn(surface, tint = tints[i++ % tints.size]) }
                Thread.sleep(20)
            }
        }
    }

    private fun stopSource() {
        sourceRunning.set(false)
        source?.join(1_000)
        source = null
    }

    /** The access units sent so far (those from packet index [from] on). */
    private fun units(from: Int = 0): List<Unit> {
        val packets = dash.packets.toList()
        val arrivals = dash.arrivalsMs.toList() // read after the packets: never shorter
        val units = mutableListOf<Unit>()
        var nal = ByteArray(0)
        var largest = 0
        for ((index, packet) in packets.withIndex().drop(from)) {
            val payloadType = packet[12].toInt() and 0x1F
            nal += if (payloadType == FU_A) {
                val start = packet[13].toInt() and 0x80 != 0
                val header = ((packet[12].toInt() and 0xE0) or (packet[13].toInt() and 0x1F)).toByte()
                (if (start) byteArrayOf(header) else ByteArray(0)) + packet.copyOfRange(14, packet.size)
            } else {
                packet.copyOfRange(12, packet.size)
            }
            largest = maxOf(largest, packet.size)
            if (packet[1].toInt() and 0x80 != 0) { // marker: last packet of the access unit
                val ts = (0..3).fold(0L) { acc, i -> (acc shl 8) or (packet[4 + i].toLong() and 0xFF) }
                units += Unit(ts, nal, index, largest, arrivals[index])
                nal = ByteArray(0)
                largest = 0
            }
        }
        return units
    }

    /**
     * The first access unit that was not complete yet when [mark] packets had been sent. With [mark] read right
     * after a call that holds the output, that unit was admitted after the hold: the output finishes the frame it
     * is sending before it lets the hold in.
     */
    private fun firstUnitAfter(mark: Int): Unit? = units().firstOrNull { it.lastPacket >= mark }

    /** Milliseconds between consecutive access units, by their RTP timestamps (90 kHz). */
    private fun stepsMs(units: List<Unit>): List<Long> = units.zipWithNext { a, b -> ((b.timestamp - a.timestamp) and 0xFFFFFFFFL) / 90 }

    // ── waiting for what the test is about, never for a fixed time ──────────────────────────────────────────
    //
    // The compositor ticks on the wall clock and the encoder is software on the emulator, so how many frames fit
    // in "3 seconds" depends on how busy the machine is (8 instead of 12 was seen). What these tests prove is
    // WHAT the frames look like (the steps of the video clock, exactly) and that they follow the wall clock
    // (the median gap, which one stalled frame cannot move); both are read after waiting for the frames, with a
    // deadline that only says "it never happened".

    /** Waits until [condition] holds; fails with [what] at the deadline (which only says "it never happened"). */
    private fun awaitThat(what: String, timeoutMs: Long = AWAIT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue("$what: not within $timeoutMs ms; ${dash.packets.size} packets, log $log", condition())
    }

    /** Waits until [enough] holds for the units sent so far and returns them. */
    private fun awaitUnits(what: String, timeoutMs: Long = AWAIT_MS, enough: (List<Unit>) -> Boolean): List<Unit> {
        awaitThat(what, timeoutMs) { enough(units()) }
        return units()
    }

    /**
     * The units of the steady state, [count] of them, for a stream whose first poll (a quarter of a second in)
     * switches the compositor from the still pace to a tick every [tickMs]. The tick that finds the new pace in
     * force still adds the old tick to its clock, and so may the one after it when it fell between the two: those
     * come out before or just after the "pace:" line, so the first two units after it are dropped.
     */
    private fun steadyUnits(tickMs: Long, count: Int): List<Unit> {
        awaitThat("the pace of $tickMs ms in force") { log.any { "pace: a tick every $tickMs ms" in it } }
        val mark = dash.packets.size
        return awaitUnits("$count frames at a tick every $tickMs ms") { all -> all.count { it.lastPacket >= mark } >= count + 2 }
            .filter { it.lastPacket >= mark }.drop(2)
    }

    /**
     * The frames follow the wall clock: the MEDIAN gap between their arrivals is about [expectedMs]. A median, so a
     * stall of the machine that delays one or two frames does not matter, while a loop that ticks at the wrong
     * rate (or whose clock runs ahead of the wall) does not pass.
     */
    private fun assertWallPace(units: List<Unit>, expectedMs: Long) {
        val gaps = units.zipWithNext { a, b -> b.arrivalMs - a.arrivalMs }.sorted()
        val median = gaps[gaps.size / 2]
        assertTrue("the median gap between frames on the wall clock is $median ms, expected about $expectedMs ms; gaps $gaps", median in expectedMs * 75 / 100..expectedMs * 130 / 100)
    }

    // ── the first thing the dash gets ──────────────────────────────────────────────────────────────────────

    @Test
    fun theFirstThingTheDashGetsIsAKeyframeWithItsParameterSets() {
        val l = newLoop()
        assertTrue("the loop did not start: $log", l.start())
        awaitUnits("the first frame") { it.isNotEmpty() }
        Thread.sleep(500)

        val first = units().first()
        assertTrue("the first access unit must be SPS + PPS + IDR in one, got NAL type ${first.nalType} of ${first.nal.size} B", first.isKeyframeBundle)
        assertEquals("Baseline profile", 0x42, first.nal[1].toInt() and 0xFF)
        android.util.Log.i("VideoLoopTest", "first SPS bytes: " + first.nal.take(8).joinToString(" ") { "%02X".format(it) })
        assertTrue("no packet may be larger than 12 + 1380 bytes, got ${first.largestPacket}", units().all { it.largestPacket <= 1392 })
        assertTrue(l.isRunning)
    }

    // ── the pace ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun untilTheDashConfirmsAFrameTheStreamStaysAtTheStillPaceEvenWithALiveSource() {
        dash.firstFrameConfirmed = false
        val l = newLoop()
        startSource()
        assertTrue(l.start())
        val all = awaitUnits("8 frames at the still pace") { it.size >= 8 }
        stopSource()

        val steps = stepsMs(all)
        assertEquals("every frame 250 ms after the one before, got $steps", setOf(250L), steps.toSet())
        assertWallPace(all, 250)
    }

    @Test
    fun onceTheDashConfirmsALiveSourceGoesOutAtTheCeiling() {
        EncoderCapacity.assumeKeepsUp(16)
        val l = newLoop()
        startSource()
        assertTrue(l.start())
        // The first quarter of a second runs at the still pace (the loop looks at the dash four times a second).
        val all = steadyUnits(tickMs = 62, count = 40)
        stopSource()

        val steps = stepsMs(all)
        assertTrue("every frame a whole number of 62 ms ticks after the one before, got $steps", steps.all { it > 0 && it % 62 == 0L })
        assertTrue("most frames one tick apart, got $steps", steps.count { it == 62L } >= steps.size * 8 / 10)
        assertWallPace(all, 62)
    }

    @Test
    fun aSourceThatGoesQuietGoesOutAtTheStillPace() {
        val l = newLoop()
        assertTrue(l.start())

        // One picture, posted before the start. A tick every 62 ms; the repeat falls on the 4th: 248 ms.
        val all = steadyUnits(tickMs = 62, count = 8)
        val steps = stepsMs(all)
        assertEquals("every repeat 248 ms after the frame before, got $steps", setOf(248L), steps.toSet())
        assertWallPace(all, 248)
    }

    @Test
    fun theVideoClockSettingSlowsTheStampsTheDashSees() {
        val l = newLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 16, clockSlowPercent = 5))
        assertTrue(l.start())

        // 248 ms of real time between repeats, stamped 5% short: 235.6 ms.
        val all = steadyUnits(tickMs = 62, count = 6)
        val steps = stepsMs(all)
        assertTrue("every step must be 235 or 236 ms, got $steps", steps.all { it == 235L || it == 236L })
        assertWallPace(all, 248) // the stamps are short of the wall clock, not the frames
    }

    // ── restarts and rebuilds ──────────────────────────────────────────────────────────────────────────────

    /**
     * The restart is made right after one of the encoder's own keyframes, when its next one is a second away:
     * the keyframe that opens the restarted stream has to be one the loop asked for.
     */
    @Test
    fun aStreamRestartAsksForAKeyframeAtOnceAndSendsNothingBeforeIt() {
        val l = newLoop()
        startSource()
        assertTrue(l.start())
        awaitUnits("the first keyframe") { all -> all.any { it.isKeyframeBundle } }
        val keyframes = units().count { it.isKeyframeBundle }
        awaitThat("the encoder's own next keyframe") { units().count { it.isKeyframeBundle } > keyframes }
        val lastOwnKeyframe = units().last { it.isKeyframeBundle }

        l.restartStream("dash restart")
        val mark = dash.packets.size
        awaitUnits("frames after the restart") { all -> all.count { it.lastPacket >= mark } >= 6 }
        stopSource()

        val first = checkNotNull(firstUnitAfter(mark)) { "nothing went out after the restart" }
        assertTrue("the first frame after a restart must be a keyframe with its parameter sets, got NAL type ${first.nalType}", first.isKeyframeBundle)
        val waitedMs = ((first.timestamp - lastOwnKeyframe.timestamp) and 0xFFFFFFFFL) / 90
        assertTrue("that keyframe came $waitedMs ms after the encoder's own: it was not asked for, the stream just waited", waitedMs <= 500)
        assertTrue("frames must keep going out after the restart", units().count { it.lastPacket >= mark } >= 5)
        assertTrue("the restart and its reason must be logged, got $log", log.any { "dash restart" in it })
    }

    /**
     * Many restarts in a row, with a live source. On an encoder that holds a frame or two (hardware ones do), a
     * restart catches a frame already inside it, which comes out after the restart and before the keyframe; it
     * must never reach the dash. On the emulator this does NOT prove the loop holds its output: the software
     * encoder never had a frame in flight in 40 restarts, and the test passed with the hold removed from
     * restartStream (a mutation where the restart does not hold the output). The hold itself is proven
     * in RtpOutputTest; here the test only shows that restarts in a row do no harm.
     */
    @Test
    fun whateverWasInTheEncoderWhenARestartCameNeverReachesTheDash() {
        val l = newLoop()
        startSource()
        assertTrue(l.start())
        Thread.sleep(800)
        val marks = (1..40).map {
            l.restartStream("restart $it")
            dash.packets.size.also { Thread.sleep(110) }
        }
        Thread.sleep(400)
        stopSource()

        val notKeyframes = marks.mapIndexedNotNull { i, mark ->
            firstUnitAfter(mark)?.takeIf { !it.isKeyframeBundle }?.let { "restart ${i + 1}: NAL type ${it.nalType}" }
        }
        assertTrue("after these restarts something other than a keyframe went out first: $notKeyframes", notKeyframes.isEmpty())
        assertTrue("the restarts must have produced keyframes, got ${units().count { it.isKeyframeBundle }}", units().count { it.isKeyframeBundle } >= 20)
    }

    @Test
    fun theCeilingOfTheSettingsCapsTheRate() {
        val l = newLoop(VideoLoop.Settings(startFps = 16, ceilingFps = 8))
        startSource()
        assertTrue(l.start())
        val all = steadyUnits(tickMs = 125, count = 18)
        stopSource()

        assertTrue("got $log", log.any { "video loop start: 8 fps (ceiling 8)" in it })
        val steps = stepsMs(all)
        assertTrue("most frames 125 ms apart, got $steps", steps.count { it == 125L } >= steps.size * 8 / 10)
        assertTrue("no frame closer than 125 ms to the one before (that would be above the ceiling), got $steps", steps.all { it >= 125L })
        assertWallPace(all, 125)
    }

    /**
     * The conservative envelope (4 a second, 200 kbps, constant, a keyframe every
     * second) runs as asked, and each 5 s line says the bike's speed and Wi-Fi.
     */
    @Test
    fun theConservativeEnvelopeRunsAsAskedAndTheLineSaysTheSpeedAndTheWifi() {
        val settings = VideoLoop.Settings(
            startFps = 4, ceilingFps = 4, bitrateBps = 200_000, bitrateMode = com.reau.app.dash.BitrateMode.CBR, keyframeSec = 1,
        )
        val l = newLoop(settings) { LinkSample(speedKmh = 72.0, wifiDbm = -60, wifiMbps = 52) }
        startSource()
        assertTrue(l.start())
        val deadline = System.currentTimeMillis() + 12_000
        while (log.none { it.startsWith("video fps target=4") && "full=true" in it } && System.currentTimeMillis() < deadline) Thread.sleep(200)
        stopSource()

        assertTrue("got $log", log.any { "video loop start: 4 fps (ceiling 4), keyframe 1s fixed, 200 kbps (200 chosen) CBR" in it })
        val line = log.first { it.startsWith("video fps target=4") && "full=true" in it }
        assertTrue(line, "speed~72km/h(max 72) wifi=-60dBm/52Mbps(worst)" in line)
        assertTrue(line, " kf=1s " in line)
    }

    /** The automatic rate: three healthy 5 s windows after a settling one, then the encoder is replaced. */
    @Test
    fun theRateClimbsToTheCeilingAndTheEncoderIsReplacedWithoutABreak() {
        EncoderCapacity.assumeKeepsUp(16)
        val l = newLoop(VideoLoop.Settings(startFps = 14, ceilingFps = 16))
        startSource()
        assertTrue(l.start())
        // Five 5 s windows (a settling one and three healthy ones) are 20 s when every one is judged; the deadline allows
        // a few windows the machine did not let count.
        val deadline = SystemClock.elapsedRealtime() + CLIMB_MS
        while (log.none { "encoder rebuilt: 16 fps" in it } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        assertTrue("the rate did not climb to 16 in $CLIMB_MS ms: ${log.filter { "video fps" in it || "rebuilt" in it }}", log.any { "encoder rebuilt: 16 fps" in it })
        val rebuilt = dash.packets.size
        awaitUnits("frames after the rebuild") { all -> all.count { it.lastPacket >= rebuilt } >= 12 }
        stopSource()

        val all = units()
        val steps = stepsMs(all)
        assertTrue("timestamps must keep increasing across the rebuild", steps.all { it in 1..1_000 })
        assertEquals("before the climb a tick is 71 ms (14 a second)", 71L, steps.drop(4).take(20).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        assertEquals("after it a tick is 62 ms (16 a second)", 62L, steps.takeLast(10).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        val lines = log.filter { it.startsWith("video fps target=") }
        assertTrue("a line every 5 s, got ${lines.size}: $lines", lines.size >= 4)
        assertTrue("the first windows are measured at 14, got ${lines.first()}", "target=14" in lines.first())
    }

    @Test
    fun stoppingEndsTheStreamAndItCanStartAgainWithAKeyframe() {
        val l = newLoop()
        assertTrue(l.start())
        awaitUnits("something out before the stop") { it.isNotEmpty() }
        l.stop()
        assertFalse(l.isRunning)
        val sent = dash.packets.size
        assertTrue("something must have gone out before the stop", sent > 0)
        checkNotNull(compositor).takeStats() // what was drawn up to the stop
        Thread.sleep(1_200) // longer than the encoder's own repeat of the last frame
        assertEquals("nothing may go out after stop()", sent, dash.packets.size)
        assertEquals("the compositor must have stopped drawing", 0, checkNotNull(compositor).takeStats().let { it.drawn + it.repeated })
        val outputThreads = Thread.getAllStackTraces().keys.filter { it.name == EncoderOutputPump.THREAD_NAME && it.isAlive }
        assertTrue("the encoder must have been released: its output thread is still alive", outputThreads.isEmpty())

        assertTrue(l.start())
        val again = awaitUnits("two frames of the second stream") { all -> all.count { it.lastPacket >= sent } >= 2 }.filter { it.lastPacket >= sent }
        assertTrue("the second stream must send frames, got ${again.size}", again.size >= 2)
        assertTrue("and open with a keyframe and its parameter sets", again.first().isKeyframeBundle)
    }

    @Test
    fun aSendThatFailsIsReportedAndTheStreamCarriesOn() {
        val l = newLoop()
        assertTrue(l.start())
        awaitUnits("something out before the failure") { it.isNotEmpty() }
        dash.failNextSend.set(true)
        awaitThat("the failure in the log") { log.any { "encoder output failing" in it } }
        val sent = dash.packets.size
        awaitThat("frames going out again") { dash.packets.size > sent }

        assertTrue("the failure must reach the log, got $log", log.any { "encoder output failing" in it && "the dash socket is gone" in it })
        assertTrue("frames must keep going out after a failed send", dash.packets.size > sent)
    }

    private companion object {
        /** How long to wait for something that has to happen (frames, a log line): a deadline, not a duration. */
        const val AWAIT_MS = 20_000L
        const val CLIMB_MS = 60_000L
        const val FU_A = 28
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }
}
