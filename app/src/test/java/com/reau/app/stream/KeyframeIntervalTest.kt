package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 22, item 1: one keyframe a second at 4, 6 and 2 fps with the encoder built once at the hint of 4 fps. The encoder counts its
 * interval in frames at the hint, so at 6 fps (the light screens') its 4 frames came to 0.67 s (1.49 keyframes a second measured on 01/11/2026).
 */
class KeyframeIntervalTest {
    // ── the pure rules ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the encoder's own interval is longer than the rider's at the fastest pace, in whole seconds`() {
        // 1 s at 6 fps on a hint of 4 is 1.5 s of encoder time: 2 s (8 frames = 1.33 s at 6 fps), never the 4 frames that come to 0.67 s.
        assertEquals(2, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 6, encoderFps = 4))
        assertEquals("a whole product still gets one more second", 4, DashLogic.encoderKeyframeSec(keyframeSec = 2, gopFps = 6, encoderFps = 4))
        assertEquals(4, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 6, encoderFps = 2))
        assertEquals(2, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 8, encoderFps = 6))
    }

    @Test
    fun `with the pace never above the encoder's rate the interval is the rider's, as before`() {
        assertEquals(1, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 4, encoderFps = 4))
        assertEquals(1, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 2, encoderFps = 4))
        assertEquals(1, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 16, encoderFps = 16))
        assertEquals(2, DashLogic.encoderKeyframeSec(keyframeSec = 2, gopFps = 8, encoderFps = 8))
    }

    @Test
    fun `the loop asks for the keyframes itself below the encoder's rate and whenever the interval was stretched`() {
        assertTrue("REDUCED", DashLogic.paceSyncNeeded(fps = 2, encoderFps = 4, gopFps = 4))
        assertTrue("4 fps with the interval stretched to 6", DashLogic.paceSyncNeeded(fps = 4, encoderFps = 4, gopFps = 6))
        assertTrue("6 fps too: the encoder's own interval is the longer one", DashLogic.paceSyncNeeded(fps = 6, encoderFps = 4, gopFps = 6))
        assertFalse("the encoder's own do", DashLogic.paceSyncNeeded(fps = 4, encoderFps = 4, gopFps = 4))
        assertFalse(DashLogic.paceSyncNeeded(fps = 16, encoderFps = 16, gopFps = 16))
    }

    @Test
    fun `the idle keyframe rule counts the lengthened interval in frames`() {
        // 4 fps hint, 2 s = 8 frames; the still pace of 4 fps idles at 500 ms: 4 s between the encoder's own: one is asked for.
        assertTrue(DashLogic.idleSyncNeeded(hintFps = 4, keyframeIntervalSec = 2, idleFrameMs = 500))
        // The rider's 1 s on a hint of 4: 4 frames at 500 ms is 2.0 s, none asked for (as before).
        assertFalse(DashLogic.idleSyncNeeded(hintFps = 4, keyframeIntervalSec = 1, idleFrameMs = 500))
    }

    // ── the governor and a model of the encoder ────────────────────────────────────────────────────────────

    private var nowMs = 0L
    private var acks = 0L

    private fun governor(lightFps: Int = 6, keyframeSec: Int = 1) =
        StreamGovernor(AutoFpsController.Plan(4, 4), fixedKeyframeSec = keyframeSec, lightScreenFps = lightFps).also { it.start(nowMs) }

    private fun far(distance: Int) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /**
     * An encoder that counts [ownFrames] frames between its own keyframes and starts the count again on every keyframe, asked for or
     * its own. A model of what the logs show, not of the chip.
     */
    private class EncoderModel(val ownFrames: Int) {
        var sinceKey = 0
        var syncPending = true
        val keyAtMs = ArrayList<Long>()

        fun frame(nowMs: Long) {
            val key = syncPending || sinceKey + 1 >= ownFrames
            syncPending = false
            if (key) {
                keyAtMs += nowMs
                sinceKey = 0
            } else {
                sinceKey++
            }
        }
    }

    /**
     * Runs [seconds] of the stream at the pace the governor asks for, feeding the model the frames it would send, the way the loop
     * does: a poll every 250 ms, its actions applied (a keyframe request marks the next frame). [light] decides the screen per second.
     * Returns the keyframes of the last [seconds] - 2 s, as a rate per second.
     */
    private fun keyframesPerSecond(
        g: StreamGovernor,
        seconds: Int,
        light: (Int) -> Boolean,
        navSample: StreamGovernor.NavSample? = null,
        ownFramesFor: (StreamGovernor) -> Int = { it.encoderFps * it.encoderKeyframeSec },
    ): Double {
        val model = EncoderModel(ownFramesFor(g))
        var owed = 0.0
        val measuredFromMs = nowMs + 2_000
        repeat(seconds * 4) {
            nowMs += 250
            owed += g.fps / 4.0
            val frames = owed.toInt()
            owed -= frames
            repeat(frames) { model.frame(nowMs) }
            acks += frames
            val actions = g.onPoll(
                StreamGovernor.Poll(
                    nowMs = nowMs, compositor = DashCompositor.Stats(frames * 2, frames, 0, 0L), framesOut = frames, bytesOut = 1_000L * frames,
                    lateFrames = 0, encodeNs = 0L, dashAcks = acks, firstFrameConfirmed = true, lightScreen = light(((nowMs / 1000) % 1000).toInt()),
                    navSample = navSample,
                ),
            )
            if (actions.any { it is Action.RequestSync }) model.syncPending = true
        }
        return model.keyAtMs.count { it >= measuredFromMs } / ((seconds * 1000 - 2_000) / 1000.0)
    }

    @Test
    fun `the map at 4 fps has a keyframe a second`() {
        val g = governor()
        assertEquals(1.0, keyframesPerSecond(g, 30, light = { false }), 0.1)
    }

    @Test
    fun `a light screen at 6 fps has a keyframe a second, not 1,5`() {
        val g = governor()
        val rate = keyframesPerSecond(g, 30, light = { true })
        assertEquals(6, g.fps)
        assertEquals("keyframes a second at 6 fps", 1.0, rate, 0.1)
    }

    @Test
    fun `going from the map to a light screen and back keeps the keyframe a second`() {
        val g = governor()
        assertEquals(1.0, keyframesPerSecond(g, 40, light = { (it / 8) % 2 == 1 }), 0.1)
    }

    @Test
    fun `whatever the encoder's own count past 6 frames, the loop's requests keep the second`() {
        // The encoder is asked for 2 s on a hint of 4 (8 frames); an encoder that took more (it rounds up) is no worse, since the clock asks.
        for (own in listOf(8, 12, 40)) {
            val g = governor()
            val rate = keyframesPerSecond(g, 30, light = { true }, ownFramesFor = { own })
            assertEquals("encoder counting $own frames at 6 fps", 1.0, rate, 0.1)
        }
    }

    @Test
    fun `the old behaviour is an encoder counting 4 frames at 6 fps with no request, which is not what is asked for now`() {
        val model = EncoderModel(ownFrames = 4)
        for (frame in 0 until 6 * 30) model.frame(frame * 1000L / 6)
        assertEquals("4 frames at 6 fps are 0.67 s: the 1.49 a second of the logs", 1.5, model.keyAtMs.size / 30.0, 0.1)
        val asked = governor()
        assertEquals("what is asked for now is 8 frames, 1.33 s at 6 fps", 8, asked.encoderFps * asked.encoderKeyframeSec)
    }

    // ── the rebuilds carry the interval ─────────────────────────────────────────────────────────────────────

    /** One 5 s window of 20 polls with [composed] frames drawn and sent, the way StreamGovernorTest feeds one. */
    private fun StreamGovernor.window(composed: Int): List<Action> = (0 until 20).flatMap { i ->
        fun share(total: Int) = total * (i + 1) / 20 - total * i / 20
        nowMs += 250
        acks += share(composed)
        onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs, compositor = DashCompositor.Stats(share(composed) * 2, share(composed), 0, 0L), framesOut = share(composed),
                bytesOut = 1_000L * share(composed), lateFrames = 0, encodeNs = 0L, dashAcks = acks, firstFrameConfirmed = true,
            ),
        )
    }

    @Test
    fun `the rebuild of a climb to a higher rate asks for the interval of the new rate`() {
        // Light pace 16 fps over an encoder that climbs 8 -> 10: 1 s at 16 fps is 1.6 s of the encoder's counting, so 2 s.
        val g = StreamGovernor(AutoFpsController.Plan(8, 12), lightScreenFps = 16).also { it.start(nowMs); it.window(composed = 1) }
        val up = (1..4).flatMap { g.window(composed = 40) }.filterIsInstance<Action.Rebuild>()
        assertEquals(1, up.size)
        assertEquals(10, up.single().fps)
        assertEquals(1, up.single().keyframeSec)
        assertEquals(2, up.single().encoderKeyframeSec)
    }

    @Test
    fun `a navigation profile switched on while running rebuilds the encoder at the full rate with the interval for the light pace`() {
        val g = StreamGovernor(AutoFpsController.Plan(8, 16), lightScreenFps = 24).also { it.start(nowMs) }
        assertEquals("without a profile the encoder is built at the automatic rate", 8, g.encoderFps)
        assertEquals(1 * 24 / 8 + 1, g.encoderKeyframeSec)
        nowMs += 250
        val actions = g.onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs, compositor = DashCompositor.Stats(0, 0, 0, 0L), framesOut = 0, bytesOut = 0L, lateFrames = 0, encodeNs = 0L,
                dashAcks = acks, firstFrameConfirmed = true, navSample = far(1_200),
            ),
        )
        val rebuild = actions.filterIsInstance<Action.Rebuild>().single()
        assertEquals(16, rebuild.fps)
        assertEquals(1 * 24 / 16 + 1, rebuild.encoderKeyframeSec)
    }

    @Test
    fun `a stream that is still asks for the idle keyframe every 2 s, since the encoder's own is 2 s of frames at the hint`() {
        val g = governor()
        var syncs = 0
        val asked = ArrayList<Long>()
        repeat(80) { // 20 s with no new picture: the compositor repeats, the pace is the idle one
            nowMs += 250
            val actions = g.onPoll(
                StreamGovernor.Poll(
                    nowMs = nowMs, compositor = DashCompositor.Stats(0, 0, 2, 0L), framesOut = if (it % 2 == 0) 1 else 0, bytesOut = 500L,
                    lateFrames = 0, encodeNs = 0L, dashAcks = acks, firstFrameConfirmed = true,
                ),
            )
            actions.filterIsInstance<Action.RequestSync>().filter { a -> a.reason == "idle" }.forEach { _ -> syncs++; asked += nowMs }
        }
        assertTrue("an idle keyframe was asked for, got $syncs", syncs >= 8)
        assertTrue("never closer than 2 s: $asked", asked.zipWithNext { a, b -> b - a }.all { it >= 2_000 })
    }

    // ── the video loop passes it to the encoder ─────────────────────────────────────────────────────────────

    @Test
    fun `the video loop builds the encoder with the governor's interval, at the start and at every rebuild`() {
        val loop = java.io.File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec)"))
        assertTrue(loop.contains("buildEncoder(out, wanted.fps, wanted.bitrateBps, wanted.encoderKeyframeSec)"))
        assertTrue(loop.contains("created.prepare(fps, bitrateBps, encoderKeyframeSec, settings.bitrateMode)"))
        assertTrue("and says what it asked for", loop.contains("encoder asked"))
    }

    @Test
    fun `the REDUCED profile at 2 fps keeps its keyframe a second`() {
        val g = governor()
        repeat(41) { g.onPoll(pollOf(far(1_200))) } // the first verdict and the dwell after it (10 s)
        g.onPoll(pollOf(far(1_450)))
        assertEquals("reduced profile", 2, g.profileFps)
        assertEquals(1.0, keyframesPerSecond(g, 30, light = { false }, navSample = far(1_450)), 0.1)
    }

    private fun pollOf(sample: StreamGovernor.NavSample): StreamGovernor.Poll {
        nowMs += 250
        return StreamGovernor.Poll(
            nowMs = nowMs, compositor = DashCompositor.Stats(0, 0, 0, 0L), framesOut = 0, bytesOut = 0L, lateFrames = 0, encodeNs = 0L,
            dashAcks = acks, firstFrameConfirmed = true, navSample = sample,
        )
    }

    @Test
    fun `the encoder is built with the stretched interval and the rebuild carries it`() {
        val g = governor()
        assertEquals(6, g.gopFps)
        assertEquals(4, g.encoderFps)
        assertEquals(2, g.encoderKeyframeSec)
        assertEquals(2, g.rebuildAction().encoderKeyframeSec)
        assertEquals(1, g.rebuildAction().keyframeSec)
    }

    @Test
    fun `without the light pace the interval is the rider's and no request is made at the encoder's rate`() {
        val g = governor(lightFps = 0)
        assertEquals(4, g.gopFps)
        assertEquals(1, g.encoderKeyframeSec)
        val actions = (1..40).flatMap { _ ->
            nowMs += 250
            acks += 1
            g.onPoll(
                StreamGovernor.Poll(
                    nowMs = nowMs, compositor = DashCompositor.Stats(2, 1, 0, 0L), framesOut = 1, bytesOut = 1_000L, lateFrames = 0, encodeNs = 0L,
                    dashAcks = acks, firstFrameConfirmed = true,
                ),
            )
        }
        assertTrue("got $actions", actions.none { it is Action.RequestSync })
    }

    @Test
    fun `a keyframe interval of 2 s keeps its 2 s at 6 fps, with the encoder asked for 4`() {
        val g = governor(keyframeSec = 2)
        assertEquals(4, g.encoderKeyframeSec)
        assertEquals(0.5, keyframesPerSecond(g, 40, light = { true }), 0.1)
    }
}
