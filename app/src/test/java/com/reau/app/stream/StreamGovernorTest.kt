package com.reau.app.stream

import com.reau.app.aa.NavGuidance
import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions of the video loop, with the clock and every measurement passed in: pace, bitrate, keyframe
 * requests, the 5 s window with its log line, and when the encoder has to be rebuilt.
 */
class StreamGovernorTest {
    private var nowMs = 0L
    private var acks = 0L

    private fun governor(startFps: Int = 16, ceiling: Int = 16, clockSlowPercent: Int = 0, bitrateCeilingBps: Int = DashConfig.MAX_STREAM_BITRATE_BPS) =
        StreamGovernor(AutoFpsController.Plan(startFps, ceiling, null), clockSlowPercent, bitrateCeilingBps = bitrateCeilingBps)

    /** One poll, 250 ms after the one before. */
    private fun StreamGovernor.poll(
        drawn: Int = 0,
        repeated: Int = 0,
        framesOut: Int = drawn + repeated,
        bytesOut: Long = framesOut * 1_000L,
        confirmed: Boolean = true,
        thermalOk: Boolean = true,
        navSample: StreamGovernor.NavSample? = null,
        keyframes: Int = 0,
        keyBytesMax: Int = 0,
        keyPacketsMax: Int = 0,
    ): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn * 2, drawn = drawn, repeated = repeated, composeNs = (drawn + repeated) * 2_000_000L),
                framesOut = framesOut, bytesOut = bytesOut, lateFrames = 0, encodeNs = framesOut * 8_000_000L,
                dashAcks = acks, firstFrameConfirmed = confirmed, thermalOk = thermalOk, navSample = navSample,
                keyframes = keyframes, keyBytesMax = keyBytesMax, keyPacketsMax = keyPacketsMax,
            ),
            // The stats text's own second (StreamGovernorStatsTest) is not what these tests are about.
        ).filterNot { it is Action.Stats }
    }

    /** One 5 s window of 20 polls with [composed] frames drawn from new pictures and [sent] coming out. */
    private fun StreamGovernor.window(
        composed: Int,
        sent: Int = composed,
        bytes: Long = sent * 1_000L,
        navSample: StreamGovernor.NavSample? = null,
    ): List<Action> =
        (0 until POLLS_PER_WINDOW).flatMap { i ->
            fun share(total: Int) = total * (i + 1) / POLLS_PER_WINDOW - total * i / POLLS_PER_WINDOW
            poll(
                drawn = share(composed), framesOut = share(sent),
                bytesOut = bytes * (i + 1) / POLLS_PER_WINDOW - bytes * i / POLLS_PER_WINDOW, navSample = navSample,
            )
        }

    /** The maneuver profile alone, [distance] null meaning no active route. */
    private fun maneuverSample(distance: Int?) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /** The first verdict at [distance], then 10 s (40 polls) more of it: the next tier change is not held back. */
    private fun StreamGovernor.keptAt(distance: Int, drawn: Int = 0) {
        repeat(41) { poll(drawn = drawn, navSample = maneuverSample(distance = distance)) }
    }

    /** A started governor with its first window open (the window opens with the first frames that come out). */
    private fun started(startFps: Int = 16, ceiling: Int = 16, bitrateCeilingBps: Int = DashConfig.MAX_STREAM_BITRATE_BPS): StreamGovernor =
        governor(startFps, ceiling, bitrateCeilingBps = bitrateCeilingBps).also {
            it.start(nowMs)
            it.poll(drawn = 1)
        }

    // ── how a stream starts ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stream starts at the still pace, at the still bitrate, asking for a keyframe`() {
        val g = governor()
        assertEquals(
            listOf(Action.SetPace(DashCompositor.Pace(250, 250, 0)), Action.SetBitrate(512_000), Action.RequestSync("stream start")),
            g.start(nowMs),
        )
        assertEquals(16, g.fps)
        assertEquals(1, g.keyframeSec)
        assertEquals(1_024_000, g.activeBitrateBps)
    }

    @Test
    fun `until the dash confirms the first frame, pictures coming raise neither the pace nor the bitrate`() {
        val g = governor().also { it.start(nowMs) }
        val actions = (1..8).flatMap { g.poll(drawn = 4, confirmed = false) }
        assertTrue("got $actions", actions.none { it is Action.SetPace || it is Action.SetBitrate })
    }

    @Test
    fun `once the dash confirms the first frame the clock ticks at the stream's rate`() {
        val g = governor().also { it.start(nowMs) }
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(62, 250, 0))), g.poll(confirmed = true))
        assertEquals("only on the change", emptyList<Action>(), g.poll(confirmed = true))
    }

    @Test
    fun `the video clock setting travels with every pace`() {
        val g = governor(clockSlowPercent = 5)
        assertEquals(Action.SetPace(DashCompositor.Pace(250, 250, 5)), g.start(nowMs).first())
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(62, 250, 5))), g.poll(confirmed = true))
    }

    // ── bitrate ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the bitrate goes up while pictures keep coming and back down 1500 ms after the last one`() {
        val g = governor().also { it.start(nowMs) }
        g.poll(confirmed = true) // pace change, nothing drawn yet
        assertEquals(listOf(Action.SetBitrate(1_024_000)), g.poll(drawn = 4)) // t = 500
        val quiet = (1..5).map { g.poll() } // t = 750 .. 1750: up to 1250 ms after the last picture
        assertTrue("still within the hold, got $quiet", quiet.all { it.isEmpty() })
        assertEquals(listOf(Action.SetBitrate(512_000)), g.poll()) // t = 2000: 1500 ms after
    }

    // ── keyframes while still ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `while the picture is still a keyframe is asked for every 2 s`() {
        val g = governor().also { it.start(nowMs) }
        val asked = (1..24).map { g.poll() }.mapIndexedNotNull { i, actions ->
            ((i + 1) * POLL_MS).takeIf { Action.RequestSync("idle") in actions }
        }
        assertEquals("in 6 s after the start", listOf(2_000L, 4_000L, 6_000L), asked)
    }

    @Test
    fun `while pictures keep coming the encoder's own keyframes are enough`() {
        val g = governor().also { it.start(nowMs) }
        val actions = (1..24).flatMap { g.poll(drawn = 4) }
        assertTrue("got $actions", actions.none { it is Action.RequestSync })
        // And the 2 s count starts when the stream goes still (1.5 s after the last picture), not when it started:
        // the first request comes 3.25 s after the last picture's poll, at the 13th quiet poll.
        val after = (1..13).map { g.poll() }
        assertFalse("a keyframe was asked for too early", after.take(12).any { a -> a.any { it is Action.RequestSync } })
        assertTrue(Action.RequestSync("idle") in after.last())
    }

    @Test
    fun `at 4 frames a second the encoder already places a keyframe every second`() {
        val g = governor(startFps = 4, ceiling = 4).also { it.start(nowMs) }
        assertTrue((1..24).flatMap { g.poll() }.none { it is Action.RequestSync })
    }

    // ── the 5 s window ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every 5 s a line records the stream's measurements`() {
        acks = 100 // what the dash had acknowledged before this stream's first window
        val g = started()
        g.syncRequested("dash restart", nowMs)
        acks += 5
        val lines = g.window(composed = 80, bytes = 600_000).filterIsInstance<Action.Log>().map { it.line }

        assertEquals("one line per window, got $lines", 1, lines.size)
        val line = lines.single()
        for (part in listOf(
            "target=16", "sent=16.0 (80frames/5.0s)", "composed=16.0", "dash=1.0", "kbps=960", "set=1024k", "frame=7500B",
            "source=160", "drawn=80", "repeated=0", "compose=2.0ms", "encode=8.0ms", "late=0", "full=true", "thermal=OK",
            "kf=1s", "sync=2[stream start:1,dash restart:1]",
        )) {
            assertTrue("'$part' missing from: $line", part in line)
        }
    }

    /** The dash may never acknowledge a frame (on a real dash it almost never does); `dash=0.0` said nothing. */
    @Test
    fun `a dash that acknowledges no frame leaves dash= out of the line`() {
        val g = started()
        val line = g.window(composed = 80, bytes = 600_000).filterIsInstance<Action.Log>().single().line
        assertFalse(line, "dash=" in line)
        assertTrue(line, "composed=16.0 kbps=960" in line)
    }

    @Test
    fun `once the dash has acknowledged a frame dash= is said in every line after, zero included`() {
        val g = started()
        acks += 5
        val first = g.window(composed = 80, bytes = 600_000).filterIsInstance<Action.Log>().single().line
        assertTrue(first, "composed=16.0 dash=1.0 kbps=960" in first)
        val second = g.window(composed = 80, bytes = 600_000).filterIsInstance<Action.Log>().single().line
        assertTrue(second, "composed=16.0 dash=0.0 kbps=960" in second)
    }

    @Test
    fun `the window's counts start again after each line`() {
        val g = started()
        g.syncRequested("dash restart", nowMs)
        g.window(composed = 80)
        val second = g.window(composed = 40, sent = 40).filterIsInstance<Action.Log>().single().line
        assertTrue(second, "sent=8.0 (40frames/5.0s)" in second && "sync=0" in second)
    }

    // ── the rate ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `three healthy windows climb one step, and the climb stops at the ceiling`() {
        val g = started(startFps = 8, ceiling = 12)
        assertTrue("settling", g.window(composed = 40).none { it is Action.Rebuild })
        assertTrue(g.window(composed = 40).none { it is Action.Rebuild })
        assertTrue(g.window(composed = 40).none { it is Action.Rebuild })
        val up = g.window(composed = 40)
        assertTrue("got $up", Action.Rebuild(fps = 10, keyframeSec = 1, bitrateBps = 1_024_000) in up)
        assertTrue("the clock must follow the new rate, got $up", Action.SetPace(DashCompositor.Pace(100, 250, 0)) in up)
        assertTrue("the rebuild comes before the pace", up.indexOfFirst { it is Action.Rebuild } < up.indexOfFirst { it is Action.SetPace })
        assertEquals(10, g.fps)

        val rebuilds = (1..12).flatMap { g.window(composed = if (g.fps == 10) 50 else 60) }.filterIsInstance<Action.Rebuild>()
        assertEquals("one more step, then the ceiling holds", listOf(Action.Rebuild(12, 1, 1_024_000)), rebuilds)
    }

    @Test
    fun `a source slower than the rate says nothing about what the phone can do`() {
        val g = started()
        val actions = (1..8).flatMap { g.window(composed = 50, sent = 50) } // 10 a second, asked 16
        assertTrue("got ${actions.filterIsInstance<Action.Rebuild>()}", actions.none { it is Action.Rebuild })
        assertEquals(16, g.fps)
        val lines = actions.filterIsInstance<Action.Log>().map { it.line }
        assertEquals(8, lines.size)
        assertTrue("got $lines", lines.all { "full=false" in it && "composed=10.0" in it })
    }

    /** At 4 a second the still pace IS the stream's rate, so every tick has a frame; it is still not a full window. */
    @Test
    fun `a stream the dash has not confirmed is not judged, whatever it delivers`() {
        val g = governor(startFps = 4, ceiling = 8).also {
            it.start(nowMs)
            it.poll(drawn = 1, confirmed = false)
        }
        val actions = (1..6).flatMap {
            (0 until POLLS_PER_WINDOW).flatMap { g.poll(drawn = 1, confirmed = false) } // 20 frames in 5 s: 4 a second
        }
        assertTrue("got ${actions.filterIsInstance<Action.Rebuild>()}", actions.none { it is Action.Rebuild })
        assertEquals(4, g.fps)
        val lines = actions.filterIsInstance<Action.Log>().map { it.line }
        assertEquals(6, lines.size)
        assertTrue("got $lines", lines.all { "full=false" in it && "composed=4.0" in it })
    }

    @Test
    fun `an encoder that falls behind for two windows brings the rate down to what it delivered`() {
        val g = started()
        g.window(composed = 80) // settling
        assertTrue(g.window(composed = 80, sent = 52).none { it is Action.Rebuild })
        val down = g.window(composed = 80, sent = 52) // 10.4 a second
        assertTrue("got $down", Action.Rebuild(fps = 10, keyframeSec = 1, bitrateBps = 1_024_000) in down)
        assertTrue(Action.SetPace(DashCompositor.Pace(100, 250, 0)) in down)
    }

    @Test
    fun `a hot phone counts as not keeping up`() {
        val g = started()
        g.window(composed = 80)
        (0 until POLLS_PER_WINDOW).forEach { _ -> g.poll(drawn = 4, thermalOk = false) }
        val down = (0 until POLLS_PER_WINDOW).flatMap { g.poll(drawn = 4, thermalOk = false) }
        assertTrue("got $down", down.any { it is Action.Rebuild && it.fps == 14 })
        assertTrue(down.filterIsInstance<Action.Log>().single().line.contains("thermal=HOT"))
    }

    @Test
    fun `a minute of tight bit budget moves the keyframe interval to 2 s`() {
        val g = started()
        // 0.8 of 1024 kbps: 512 000 bytes in 5 s.
        val rebuilds = (1..13).flatMap { g.window(composed = 80, bytes = 512_000) }.filterIsInstance<Action.Rebuild>()
        assertEquals(listOf(Action.Rebuild(fps = 16, keyframeSec = 2, bitrateBps = 1_024_000)), rebuilds)
        assertEquals(2, g.keyframeSec)
    }

    // ── the navigation-driven quality profile ────────────────────────────────────────────────────────

    @Test
    fun `the maneuver override jumps straight to the ceiling, skipping the climb`() {
        val g = started(startFps = 8, ceiling = 16)
        val actions = g.poll(navSample = maneuverSample(distance = 500))
        assertTrue("got $actions", Action.Rebuild(fps = 16, keyframeSec = 1, bitrateBps = 1_024_000) in actions)
        assertEquals(16, g.fps)
    }

    @Test
    fun `the reduced tier halves the pace only, after the hysteresis, with no new encoder`() {
        val g = started(startFps = 16, ceiling = 16)
        g.keptAt(1200)
        val firstFar = g.poll(navSample = maneuverSample(distance = 1200)) // first far sample: sets the baseline, stays FULL
        assertTrue("got $firstFar", firstFar.none { it is Action.Rebuild }) // the encoder already is the full rate's
        val reduced = g.poll(navSample = maneuverSample(distance = 1450)) // 250 m past the 1200 baseline: now REDUCED
        assertTrue("got $reduced", reduced.none { it is Action.Rebuild })
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(125, 250)) in reduced)
        assertEquals(8, g.fps)
        assertEquals("the encoder keeps its full rate", 16, g.encoderFps)
        assertEquals("the encoder is built for the full bitrate, its live setting is the tier's", 1_024_000, g.activeBitrateBps)
    }

    @Test
    fun `a tier change is only a pace change, however many times it flips`() {
        val g = started(startFps = 16, ceiling = 16)
        g.poll(navSample = maneuverSample(distance = 1200))
        // each tier is kept at least NAV_DWELL_MS (10 s, 40 polls), so a flip waits for it
        val flips = listOf(1450, 400, 1500, 1750, 300).flatMap { d -> (1..41).flatMap { g.poll(navSample = maneuverSample(distance = d)) } }
        assertTrue("got ${flips.filterIsInstance<Action.Rebuild>()}", flips.none { it is Action.Rebuild })
        assertEquals("one pace change per flip, four of them", 4, flips.count { it is Action.SetPace })
    }

    // ── once in a tier, it stays at least 10 s (a speed estimate can wobble across its band every few seconds) ──

    /** The line of a tier change (the 5 s stats lines are logs too). */
    private fun Action.isProfileLog() = this is Action.Log && line.startsWith("nav profile:")

    /** A speed-only sample: 55 km/h and under is FULL, 65 and over REDUCED, the dead band keeps what it was. */
    private fun speedSample(kmh: Double?) = StreamGovernor.NavSample(
        maneuverProfileEnabled = false, speedProfileEnabled = true, distanceToManeuverMeters = null, speedKmh = kmh,
    )

    @Test
    fun `a tier, once changed to, is kept for 10 s before it can change again`() {
        val g = started(startFps = 16, ceiling = 16)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED, at once: the first change has nothing before it
        assertEquals(8, g.fps)
        val held = (1..39).flatMap { g.poll(navSample = maneuverSample(distance = 400)) } // wants FULL, 9.75 s later
        assertEquals("still REDUCED", 8, g.fps)
        assertTrue("nothing changes while it is kept, got $held", held.none { it is Action.SetPace || it.isProfileLog() })
        val after = g.poll(navSample = maneuverSample(distance = 400)) // 10 s
        assertEquals("FULL now", 16, g.fps)
        assertTrue("got $after", after.any { it is Action.SetPace })
        assertTrue(after.filterIsInstance<Action.Log>().single { it.isProfileLog() }.line.startsWith("nav profile: REDUCED -> FULL (data)"))
    }

    @Test
    fun `a blip back to the other tier inside the 10 s is never seen`() {
        val g = started(startFps = 16, ceiling = 16)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED
        val blip = (1..8).flatMap { g.poll(navSample = maneuverSample(distance = 400)) } + // 2 s wanting FULL
            (1..8).flatMap { g.poll(navSample = maneuverSample(distance = 1450)) } // and REDUCED again
        assertEquals(8, g.fps)
        assertTrue("no pace change, no log line, got $blip", blip.none { it is Action.SetPace || it.isProfileLog() })
    }

    @Test
    fun `a speed that wobbles across its band every 3 s changes the tier once in 10 s, not every time`() {
        val g = started(startFps = 16, ceiling = 16)
        val changes = (0 until 10).flatMap { i ->
            (1..12).flatMap { g.poll(navSample = speedSample(if (i % 2 == 0) 72.0 else 47.0)) } // 3 s of each
        }.count { it.isProfileLog() }
        // 30 s: the first change at once, then one at most every 10 s (without the 10 s it would be 10)
        assertTrue("got $changes", changes in 3..4)
    }

    @Test
    fun `the first verdict is taken at once and starts the wait, and coming back from no data is taken at once`() {
        val g = started(startFps = 16, ceiling = 16)
        g.poll(navSample = maneuverSample(distance = 500)) // FULL, the first verdict: at once
        assertEquals(16, g.fps)
        g.poll(navSample = maneuverSample(distance = 1200))
        g.poll(navSample = maneuverSample(distance = 1450))
        assertEquals("REDUCED waits: FULL was taken 0.5 s ago", 16, g.fps)
        (1..38).forEach { g.poll(navSample = maneuverSample(distance = 1450)) }
        assertEquals("10 s after the first verdict: REDUCED", 8, g.fps)
        (1..28).forEach { g.poll(navSample = maneuverSample(distance = null)) } // 7 s with no data: the automatic rate
        assertTrue("the hold ended inside the 10 s: the wait never delays the automatic rate", g.fps == 16)
        g.poll(navSample = maneuverSample(distance = 1500))
        g.poll(navSample = maneuverSample(distance = 1750))
        assertEquals("a tier taken after no data is taken at once", 8, g.fps)
    }

    /**
     * A long road to a far maneuver, synthetic: 12 km to the turn, whole-km steps above 10 km, then 100 m steps every 3
     * to 6 s (60 to 120 km/h) down to 1 km; both switches on, Pessimista. With the maneuver profile counting only a
     * distance that grew and the speed going unknown between two 100 m steps, this swapped tiers every 10 s.
     */
    @Test
    fun `a long road to a far maneuver goes REDUCED once and stays so, not a swap every 10 s`() {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 400_000)
        val speed = NavSpeed()
        val t0 = nowMs
        val steps = buildList {
            add(0 to 12_000)
            add(6_000 to 11_000) // a whole-km step: no speed of 600 km/h
            val gaps = listOf(3_100, 4_200, 5_300, 3_600, 6_100, 2_900)
            var t = 70_000
            var d = 10_900
            var i = 0
            while (d >= 1_000) {
                add(t to d)
                t += gaps[i++ % gaps.size]
                d -= 100
            }
        }
        var next = 0
        val changes = mutableListOf<String>()
        while (nowMs - t0 < steps.last().first + 2_000) {
            val now = nowMs + POLL_MS
            while (next < steps.size && t0 + steps[next].first <= now) {
                speed.onGuidance(NavGuidance(active = true, distanceToManeuverMeters = steps[next].second), t0 + steps[next].first)
                next++
            }
            val sample = StreamGovernor.NavSample(
                maneuverProfileEnabled = true, speedProfileEnabled = true, distanceToManeuverMeters = steps.getOrNull(next - 1)?.second,
                speedKmh = speed.kmhAt(now), mode = NavProfileMode.PESSIMISTA,
            )
            changes += g.poll(drawn = 1, navSample = sample).filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith("nav profile:") }
        }
        assertEquals("got $changes", 2, changes.size)
        assertTrue(changes[0], changes[0].startsWith("nav profile: none -> FULL"))
        assertTrue(changes[1], changes[1].startsWith("nav profile: FULL -> REDUCED"))
        assertEquals(2, g.fps)
    }

    @Test
    fun `the tier change goes to the log, with what the encoder stays at`() {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 200_000)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        val line = g.poll(navSample = maneuverSample(distance = 1450)).filterIsInstance<Action.Log>().single().line
        assertEquals("nav profile: FULL -> REDUCED (data), pace 2 fps, encoder 4 fps", line)
    }

    @Test
    fun `with no data the last tier is held for 7 s, then the automatic rate comes back`() {
        val g = started(startFps = 8, ceiling = 24)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED: 12 fps
        assertEquals(12, g.fps)
        val gap = (1..27).flatMap { g.poll(navSample = maneuverSample(distance = null)) } // 6.75 s with no distance
        assertEquals("held", 12, g.fps)
        assertTrue("nothing changes while it holds, got $gap", gap.none { it is Action.SetPace || it is Action.Rebuild })
        val back = g.poll(navSample = maneuverSample(distance = null)) // 7 s
        assertEquals("the automatic rate (8), not the ceiling", 8, g.fps)
        assertTrue("got $back", back.none { it is Action.Rebuild })
    }

    @Test
    fun `a gap shorter than the hold, then data again, never leaves the tier`() {
        val g = started(startFps = 16, ceiling = 16)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED
        val actions = (1..20).flatMap { g.poll(navSample = maneuverSample(distance = null)) } + // 5 s with no data
            g.poll(navSample = maneuverSample(distance = 1500))
        assertEquals(8, g.fps)
        assertTrue("got $actions", actions.none { it is Action.SetPace || it is Action.Rebuild })
    }

    @Test
    fun `switching the profiles off ends the hold at once`() {
        val g = started(startFps = 16, ceiling = 16)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED
        g.poll(navSample = maneuverSample(distance = 1450).copy(maneuverProfileEnabled = false))
        assertEquals("the automatic rate again, now", 16, g.fps)
    }

    @Test
    fun `at a pace below the encoder's rate a keyframe is asked for at the rider's interval`() {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 200_000)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED: 2 fps on a 4 fps encoder
        val syncs = (1..40).flatMap { g.poll(drawn = 1, navSample = maneuverSample(distance = 1450)) }.filterIsInstance<Action.RequestSync>()
        assertEquals("one a second over 10 s, about", 10.0, syncs.size.toDouble(), 1.0)
        assertTrue(syncs.all { it.reason == "pace" })
    }

    @Test
    fun `at the full pace no keyframe is asked for, the encoder places its own`() {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 200_000)
        val syncs = (1..40).flatMap { g.poll(drawn = 1, navSample = maneuverSample(distance = 500)) }.filterIsInstance<Action.RequestSync>()
        assertTrue("got $syncs", syncs.isEmpty())
    }

    @Test
    fun `a profile switched on or off while running does replace the encoder`() {
        val g = started(startFps = 8, ceiling = 16)
        val on = g.poll(navSample = maneuverSample(distance = 500))
        assertTrue("got $on", Action.Rebuild(fps = 16, keyframeSec = 1, bitrateBps = 1_024_000) in on)
        val off = g.poll(navSample = maneuverSample(distance = 500).copy(maneuverProfileEnabled = false))
        assertTrue("got $off", Action.Rebuild(fps = 8, keyframeSec = 1, bitrateBps = 1_024_000) in off)
    }

    @Test
    fun `a tier that does not change asks for no rebuild`() {
        val g = started(startFps = 8, ceiling = 16)
        g.poll(navSample = maneuverSample(distance = 500))
        val actions = g.poll(navSample = maneuverSample(distance = 400)) // still under 1000 m: still FULL
        assertTrue("got $actions", actions.none { it is Action.Rebuild })
    }

    @Test
    fun `at the conservative 4 fps 200 kbps ceiling, REDUCED still feeds 2 fps, past the app's general floor, on the same encoder`() {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 200_000) // the conservative default
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        val reduced = g.poll(navSample = maneuverSample(distance = 1450))
        assertTrue("got $reduced", reduced.none { it is Action.Rebuild })
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(500, 1000)) in reduced)
        assertEquals(2, g.fps)
        assertEquals(4, g.encoderFps)
        assertEquals(200_000, g.activeBitrateBps)
    }

    @Test
    fun `Otimista (the default) takes the most generous of the two switches`() {
        val g = started(startFps = 8, ceiling = 16)
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        val actions = g.poll(
            navSample = StreamGovernor.NavSample(
                maneuverProfileEnabled = true, speedProfileEnabled = true,
                distanceToManeuverMeters = 1450, speedKmh = 40.0, // maneuver alone: REDUCED; speed alone: FULL
                mode = NavProfileMode.OTIMISTA,
            ),
        )
        assertEquals("speed's FULL wins over maneuver's REDUCED", 16, g.fps)
        assertTrue("got $actions", actions.none { it is Action.Rebuild }) // was already FULL, nothing to rebuild
    }

    @Test
    fun `Pessimista takes the least generous of the two switches`() {
        val g = started(startFps = 8, ceiling = 16)
        g.keptAt(500) // near: alone FULL, kept 10 s
        val actions = g.poll(
            navSample = StreamGovernor.NavSample(
                maneuverProfileEnabled = true, speedProfileEnabled = true,
                distanceToManeuverMeters = 500, speedKmh = 70.0, // maneuver alone: FULL; speed alone: REDUCED
                mode = NavProfileMode.PESSIMISTA,
            ),
        )
        assertEquals("speed's REDUCED wins over maneuver's FULL", 8, g.fps)
        assertTrue("got $actions", actions.none { it is Action.Rebuild })
        assertTrue("got $actions", Action.SetPace(DashCompositor.Pace(125, 250)) in actions)
    }

    @Test
    fun `either switch alone is unaffected by Otimista vs Pessimista`() {
        val otimista = started(startFps = 8, ceiling = 16)
        otimista.poll(navSample = maneuverSample(distance = 500).copy(mode = NavProfileMode.OTIMISTA))
        val pessimista = started(startFps = 8, ceiling = 16)
        pessimista.poll(navSample = maneuverSample(distance = 500).copy(mode = NavProfileMode.PESSIMISTA))
        assertEquals(otimista.fps, pessimista.fps)
        assertEquals(16, otimista.fps)
    }

    @Test
    fun `autoFps freezes without error while the override differs from it, and resumes where it froze once the override clears`() {
        val g = started(startFps = 8, ceiling = 24) // autoFps.currentFps starts, and stays, at 8 unless judged
        g.keptAt(1200) // the first verdict, FULL, kept 10 s: a change may come at once now
        g.poll(navSample = maneuverSample(distance = 1450)) // REDUCED: snapStreamFps(24 / 2) = 12, unlike autoFps's 8
        assertEquals(12, g.fps)
        val duringOverride = (1..4).flatMap { g.window(composed = 48, navSample = maneuverSample(distance = 1450)) }
        assertTrue(
            "autoFps's own climb must not fire while frozen, got ${duringOverride.filterIsInstance<Action.Rebuild>()}",
            duringOverride.none { it is Action.Rebuild },
        )
        assertEquals(12, g.fps)
        (1..28).forEach { g.poll(navSample = maneuverSample(distance = null)) } // the route ends: 7 s held, then no override
        assertEquals("back to autoFps, frozen at 8 the whole time — not reset, not the ceiling", 8, g.fps)
    }

    // ── the two profiles: 4 fps / 400 kbps and 2 fps / 200 kbps ───────────────────────────────────────────

    private fun reducedGovernor(): StreamGovernor {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 400_000)
        g.keptAt(1200, drawn = 1) // the first verdict, FULL, kept 10 s: a change may come at once now
        return g
    }

    /**
     * A ride log: `nav profile: FULL -> REDUCED (data), pace 2 fps, encoder 4 fps` with the bitrate still
     * `set=400k`. Profile 2 is 2 fps at 200 kbps ([DashConfig.PROFILE_REDUCED_BPS]), profile 1 4 fps at 400 kbps.
     */
    @Test
    fun `going to profile 2 sets its bitrate with its pace, with no new encoder, and coming back to profile 1 sets profile 1's`() {
        val g = reducedGovernor()
        val reduced = g.poll(drawn = 1, navSample = maneuverSample(distance = 1450))
        assertEquals(2, g.fps)
        assertTrue("got $reduced", Action.SetBitrate(DashConfig.PROFILE_REDUCED_BPS) in reduced)
        assertEquals(200_000, DashConfig.PROFILE_REDUCED_BPS)
        assertTrue("never a new encoder for it, got $reduced", reduced.none { it is Action.Rebuild })

        // Profile 2 is held 10 s (40 polls) before the other may be taken: nothing is set meanwhile.
        val held = (1..39).flatMap { g.poll(drawn = 1, navSample = maneuverSample(distance = 400)) }
        assertTrue("got $held", held.none { it is Action.SetBitrate || it is Action.Rebuild })
        assertEquals("still profile 2", 2, g.fps)

        val back = g.poll(drawn = 1, navSample = maneuverSample(distance = 400)) // near: profile 1, after its 10 s
        assertEquals(4, g.fps)
        assertTrue("got $back", Action.SetBitrate(DashConfig.PROFILE_FULL_BPS) in back)
        assertTrue("never a new encoder for it, got $back", back.none { it is Action.Rebuild })
    }

    @Test
    fun `profile 2 keeps its bitrate when the picture goes still, and profile 1 still halves its own`() {
        val g = reducedGovernor()
        g.poll(drawn = 1, navSample = maneuverSample(distance = 1450)) // profile 2, moving
        val still = (1..12).flatMap { g.poll(navSample = maneuverSample(distance = 1450)) }
        assertTrue("200 kbps is profile 2's whole and the still one too, got $still", still.none { it is Action.SetBitrate })

        val full = reducedGovernor()
        full.poll(drawn = 1, navSample = maneuverSample(distance = 1200))
        val quiet = (1..12).flatMap { full.poll(navSample = maneuverSample(distance = 1200)) }
        assertEquals(listOf(Action.SetBitrate(200_000)), quiet.filterIsInstance<Action.SetBitrate>())
    }

    @Test
    fun `profile 2's bitrate is its own whatever the ceiling of profile 1`() {
        val g = started(startFps = 16, ceiling = 16) // ceiling 1 024 000
        g.keptAt(1200, drawn = 1)
        val reduced = g.poll(drawn = 1, navSample = maneuverSample(distance = 1450))
        assertTrue("got $reduced", Action.SetBitrate(DashConfig.PROFILE_REDUCED_BPS) in reduced)
    }

    @Test
    fun `the 5 s line says how many keyframes, and the biggest one in bytes and packets`() {
        val g = started(startFps = 4, ceiling = 4, bitrateCeilingBps = 400_000)
        val actions = (0 until POLLS_PER_WINDOW).flatMap { i ->
            g.poll(
                drawn = 1, keyframes = if (i % 10 == 0) 1 else 0,
                keyBytesMax = if (i == 0) 9_000 else if (i == 10) 14_500 else 0,
                keyPacketsMax = if (i == 0) 7 else if (i == 10) 11 else 0,
            )
        }
        val line = actions.filterIsInstance<Action.Log>().first { it.line.startsWith("video fps") }.line
        assertTrue(line, " idr=2(max 14500B/11pkt) " in line)
    }

    private companion object {
        const val POLL_MS = 250L
        const val POLLS_PER_WINDOW = 20
    }
}
