package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.stream.escada.EscadaAutoFps
import com.reau.app.stream.escada.EscadaGovernor
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plan 27, layer 3: the governor of today beside the governor of before the fps ladder was cut (the oracle, `escada/EscadaGovernor.kt`:
 * the old code with only its names changed). Both get the very same poll, one poll at a time, and must answer the same: the same
 * actions, and the same eight values `VideoLoop` reads. The one thing allowed to differ is the text in brackets after `auto=4fps` in the
 * window's line (the owner's choice, 14/11/2026), hidden on both sides before comparing ([GovernorTrace.withoutLadderState]).
 *
 * The golden traces (GovernadorOuroTest) hold 17 cases and 66 sequences for good; this one runs many more sequences, and says at which
 * poll of which seed the two parted. It is provisional: it leaves with the oracle in step G3, once the cut was seen on the phone.
 */
class GovernadorLadoALadoTest {
    /**
     * The two governors on one bench. The polls are made as `GovernorBench` makes them (the same small model of the compositor and the
     * encoder, following the pace the governor set), once, and handed to both.
     */
    private class Pair(private val config: GovernorConfig, private val said: String) {
        val now: StreamGovernor = config.governor()

        /** The governor of before the cut, made as `GovernorConfig.governor()` made it in step G0. */
        val before = EscadaGovernor(
            EscadaAutoFps.plan(DashConfig.AUTO_FPS_START, ThermalCeiling.capFor(COOL, config.ceilingFps)), 0, EscadaGovernor.WINDOW_MS,
            config.bitrateBps, config.keyframeSec, lightScreenFps = config.lightScreenFps,
        )
        var nowMs = 0L
        var polls = 0
        var windows = 0
        val brackets = HashSet<String>()
        private var pace = DashCompositor.Pace(POLL_MS, POLL_MS)
        private var tickCarryMs = 0L
        private var sourceCarry = 0
        private var deliveryCarry = 0
        private var acks = 0L
        private var keyframeDue = false
        private var framesSinceKey = 0

        fun start() = same("start", now.start(nowMs), before.start(nowMs).map { it.asNow() })

        fun run(steps: List<Step>) {
            start()
            for (step in steps) {
                when (step) {
                    is Step.Note -> Unit
                    is Step.Sync -> {
                        now.syncRequested(step.reason, nowMs)
                        before.syncRequested(step.reason, nowMs)
                        keyframeDue = true
                        sameReads("syncRequested(${step.reason})")
                    }
                    Step.EncoderFailed -> {
                        assertEquals("$said, after poll $polls ($nowMs ms): rebuildAction() of an encoder that failed", now.rebuildAction(), before.rebuildAction().asNow())
                        keyframeDue = true
                    }
                    is Step.Poll -> poll(step.scene)
                }
            }
        }

        private fun poll(scene: Scene) {
            polls++
            nowMs += POLL_MS
            tickCarryMs += POLL_MS
            val interval = if (scene.moving) pace.activeMs else pace.idleMs
            val ticks = (tickCarryMs / interval).toInt()
            tickCarryMs %= interval
            val drawn = if (scene.moving) whole(ticks * scene.sourcePermille, sourceCarry).also { sourceCarry = it.second }.first else 0
            val repeated = if (scene.moving) 0 else ticks
            val framesOut = whole((drawn + repeated) * scene.deliveryPermille, deliveryCarry).also { deliveryCarry = it.second }.first
            val keyframe = framesOut > 0 && (keyframeDue || framesSinceKey + framesOut >= now.encoderFps * now.encoderKeyframeSec)
            framesSinceKey = if (keyframe) 0 else framesSinceKey + framesOut
            if (keyframe) keyframeDue = false
            val keyBytes = if (keyframe) scene.bytesPerFrame * KEYFRAME_TIMES else 0
            acks += when (scene.acks) {
                Acks.NONE -> 0
                Acks.KEYFRAMES -> if (keyframe) 1 else 0
                Acks.EVERY_FRAME -> framesOut
            }
            val p = StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(
                    sourceFrames = if (scene.moving) ticks else 0, drawn = drawn, repeated = repeated, composeNs = (drawn + repeated) * COMPOSE_NS, wakeups = if (scene.moving) ticks else 0,
                ),
                framesOut = framesOut,
                bytesOut = framesOut.toLong() * scene.bytesPerFrame + if (keyframe) keyBytes - scene.bytesPerFrame else 0,
                keyframes = if (keyframe) 1 else 0, keyBytesMax = keyBytes, keyPacketsMax = if (keyframe) keyBytes / RTP_PAYLOAD + 1 else 0,
                lateFrames = 0, encodeNs = framesOut * ENCODE_NS,
                dashAcks = acks, firstFrameConfirmed = scene.confirmed,
                thermalOk = !scene.overCeiling && ThermalCeiling.allows(scene.thermalStatus, now.profileFps, config.ceilingFps),
                link = LinkSample(scene.route?.kmh, WIFI_DBM, WIFI_MBPS),
                navSample = scene.route?.let { StreamGovernor.NavSample(it.switchesOn, it.switchesOn, it.meters, it.kmh, NavProfileMode.PESSIMISTA) },
                lightScreen = VideoLoop.lightScreenOn(config.lightScreenFps, scene.thermalStatus) { scene.menu },
            )
            val actions = now.onPoll(p)
            same("poll $polls ($nowMs ms, $scene)", actions, before.onPoll(p.asBefore()).map { it.asNow() })
            for (action in actions) {
                when (action) {
                    is Action.SetPace -> pace = action.pace
                    is Action.RequestSync, is Action.Rebuild -> keyframeDue = true
                    is Action.Log -> if (action.line.startsWith(GovernorTrace.WINDOW_LINE)) window(action.line)
                    else -> Unit
                }
            }
        }

        /** The bracket of today's line says `not judged` exactly when the line says `full=false`, and one of the three texts always. */
        private fun window(line: String) {
            windows++
            val bracket = GovernorTrace.ladderStateOf(line).orEmpty()
            assertTrue("$said, poll $polls: $line", bracket == "ok" || bracket == "not judged" || bracket.startsWith("short: "))
            assertEquals("$said, poll $polls: $line", " full=false " in line, bracket == "not judged")
            brackets += bracket.substringBefore(' ')
        }

        private fun same(at: String, today: List<Action>, old: List<Action>) {
            val (t, o) = today.map(::hidden) to old.map(::hidden)
            if (t != o) {
                val i = (0 until maxOf(t.size, o.size)).first { t.getOrNull(it) != o.getOrNull(it) }
                fail("$said: the two governors part at $at, action ${i + 1}:\n  today:  ${t.getOrNull(i)?.let(GovernorTrace::text)}\n  before: ${o.getOrNull(i)?.let(GovernorTrace::text)}")
            }
            sameReads(at)
        }

        private fun sameReads(at: String) = assertEquals("$said: what the video loop reads, after $at", readsOf(before), GovernorTrace.reads(now))

        private fun hidden(action: Action): Action = if (action is Action.Log) Action.Log(GovernorTrace.withoutLadderState(action.line)) else action

        private fun whole(thousandths: Int, carry: Int): kotlin.Pair<Int, Int> = (thousandths + carry).let { it / PERMILLE to it % PERMILLE }
    }

    private fun sideBySide(config: GovernorConfig, name: String, steps: List<Step>): Pair = Pair(config, "${config.name}, $name").also { it.run(steps) }

    @Test
    fun `the 17 cases of the table get the same answers from both governors, at 400 and at 200 kbps`() {
        for (config in GovernorConfig.REAL) {
            for (case in GovernorCases.ALL) assertTrue(case.file, sideBySide(config, "case ${case.file}", case.steps).polls > 0)
            sideBySide(config, "case 07 for 11 minutes", GovernorCases.SPEED_WOBBLE_11_MIN)
        }
    }

    @Test
    fun `300 seeded sequences of 10 minutes get the same answers from both governors, poll by poll, at 400 and at 200 kbps`() {
        var windows = 0
        val brackets = HashSet<String>()
        for (config in GovernorConfig.REAL) {
            for (seed in 1..REAL_SEEDS) {
                val pair = sideBySide(config, "seed $seed", GovernorSequences.of(seed))
                assertEquals(GovernorSequences.POLLS, pair.polls)
                windows += pair.windows
                brackets += pair.brackets
            }
        }
        assertTrue("the sequences closed windows: $windows", windows > REAL_SEEDS * 100)
        assertEquals("and the bracket showed its three texts", setOf("ok", "not", "short:"), brackets)
    }

    @Test
    fun `and so do the settings the app does not use, no light-screen pace, a keyframe every 2 s and the automatic keyframe`() {
        // The automatic keyframe interval (0) is the one case where a poll still asks for a new encoder: both must ask for it at the same poll.
        val automaticKeyframe = GovernorConfig.REAL_400.copy(name = "400 kbps, automatic keyframe", keyframeSec = DashConfig.KEYFRAME_INTERVAL_AUTO)
        for (config in listOf(GovernorConfig.NO_LIGHT_SCREEN, GovernorConfig.KEYFRAME_2S, automaticKeyframe)) {
            for (seed in 1..OTHER_SEEDS) sideBySide(config, "seed $seed", GovernorSequences.of(seed))
        }
    }

    /** The comparison would prove nothing if the oracle were not the ladder: where the ladder can be reached (a ceiling of 16, which the app never has), it climbs and the governor of today does not. */
    @Test
    fun `the oracle is the governor with the ladder, above the app's ceiling it climbs from 8 where today's runs at the ceiling`() {
        val before = EscadaGovernor(EscadaAutoFps.plan(DashConfig.AUTO_FPS_START, 16)).also { it.start(0) }
        val now = StreamGovernor(16).also { it.start(0) }
        assertEquals(8, before.fps)
        assertEquals(16, now.fps)
        var nowMs = 0L
        var rebuilds = 0
        repeat(CLIMB_POLLS) {
            nowMs += POLL_MS
            // Two frames a poll: whole windows at 8 fps, with nothing short.
            val p = StreamGovernor.Poll(nowMs, DashCompositor.Stats(2, 2, 0, 0L), framesOut = 2, bytesOut = 2_000L, lateFrames = 0, encodeNs = 0L, dashAcks = 0L, firstFrameConfirmed = true)
            rebuilds += before.onPoll(p.asBefore()).count { it is EscadaGovernor.Action.Rebuild }
            assertTrue(now.onPoll(p).none { it is Action.Rebuild })
        }
        assertEquals("the ladder went up a step and had the encoder replaced", 10 to 1, before.fps to rebuilds)
        assertEquals(16, now.fps)
    }

    private companion object {
        const val REAL_SEEDS = 300
        const val OTHER_SEEDS = 40
        const val CLIMB_POLLS = 100
        const val COOL = 0
        const val POLL_MS = 250L
        const val PERMILLE = 1000
        const val KEYFRAME_TIMES = 3
        const val RTP_PAYLOAD = 1_400
        const val COMPOSE_NS = 2_000_000L
        const val ENCODE_NS = 8_000_000L
        const val WIFI_DBM = -58
        const val WIFI_MBPS = 72

        fun StreamGovernor.Poll.asBefore() = EscadaGovernor.Poll(
            nowMs = nowMs, compositor = compositor, framesOut = framesOut, bytesOut = bytesOut, keyframes = keyframes, keyBytesMax = keyBytesMax,
            keyPacketsMax = keyPacketsMax, lateFrames = lateFrames, encodeNs = encodeNs, dashAcks = dashAcks, firstFrameConfirmed = firstFrameConfirmed,
            thermalOk = thermalOk, link = link,
            navSample = navSample?.let { EscadaGovernor.NavSample(it.maneuverProfileEnabled, it.speedProfileEnabled, it.distanceToManeuverMeters, it.speedKmh, it.mode) },
            lightScreen = lightScreen, encode = encode,
        )

        fun EscadaGovernor.Action.asNow(): Action = when (this) {
            is EscadaGovernor.Action.SetPace -> Action.SetPace(pace)
            is EscadaGovernor.Action.SetBitrate -> Action.SetBitrate(bps)
            is EscadaGovernor.Action.RequestSync -> Action.RequestSync(reason)
            is EscadaGovernor.Action.Rebuild -> asNow()
            is EscadaGovernor.Action.Log -> Action.Log(line)
            is EscadaGovernor.Action.Stats -> Action.Stats(sentFps, kbps)
        }

        fun EscadaGovernor.Action.Rebuild.asNow() = Action.Rebuild(fps, keyframeSec, bitrateBps, encoderKeyframeSec)

        /** The eight values of the oracle, in the words of [GovernorTrace.reads]. */
        fun readsOf(g: EscadaGovernor): String =
            "fps=${g.fps} profileFps=${g.profileFps} encoderFps=${g.encoderFps} gopFps=${g.gopFps} encoderKeyframeSec=${g.encoderKeyframeSec} " +
                "keyframeSec=${g.keyframeSec} activeBitrateBps=${g.activeBitrateBps} rebuildAction=${GovernorTrace.text(g.rebuildAction().asNow())}"
    }
}
