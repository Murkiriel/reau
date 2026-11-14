package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.fuzz.Seeded
import com.reau.app.settings.REauSettings
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import java.util.Locale
import java.util.Random

/*
 * The bench the proof of plan 27 (the cut of the fps ladder) runs the governor on: what surrounds the stream at each poll (a [Scene]),
 * a small model of the compositor and the encoder that turns the governor's own pace into frames ([GovernorBench]), the trace of what
 * the governor answered ([GovernorTrace]), the table of cases taken from the ride logs ([GovernorCases]) and the seeded sequences
 * ([GovernorSequences]). Nothing here is a ride: every number is made up, and the clock starts at 0.
 *
 * Used by EscadaInalcancavelTest and GovernadorOuroTest (step G0), and by the side-by-side test of step G1.
 */

/** One stream configuration. The two real ones are what `REauSettings.sanitized()` leaves: [REAL_400] and, with the adaptive bitrate experiment, [REAL_200]. */
internal data class GovernorConfig(val name: String, val ceilingFps: Int, val bitrateBps: Int, val keyframeSec: Int, val lightScreenFps: Int) {
    companion object {
        /** The stream as the app starts it: the rider's settings, sanitized, through `VideoLoop.Settings.withRider`. */
        fun real(adaptiveBitrate: Boolean): GovernorConfig {
            val s = VideoLoop.Settings().withRider(REauSettings(experimentAdaptiveBitrate = adaptiveBitrate).sanitized())
            return GovernorConfig("${s.bitrateBps / 1000} kbps", s.ceilingFps, s.bitrateBps, s.keyframeSec, s.lightScreenFps)
        }

        val REAL_400 = real(adaptiveBitrate = false)
        val REAL_200 = real(adaptiveBitrate = true)
        val REAL = listOf(REAL_400, REAL_200)

        /** Two the app does not use, kept in the seeded sequences to be sure: no light-screen pace, and a keyframe every 2 s. */
        val NO_LIGHT_SCREEN = REAL_400.copy(name = "400 kbps, light screen off", lightScreenFps = 0)
        val KEYFRAME_2S = REAL_400.copy(name = "400 kbps, keyframe 2 s", keyframeSec = 2)
    }

    /**
     * The governor as `VideoLoop.start` builds it for a cool phone. THE ONLY LINE OF THE PROOF THAT STEP G1 MAY CHANGE: there the plan
     * becomes the full pace (`StreamGovernor(ceiling, 0, …)`). Nothing else of this file, and no golden file, moves with the cut.
     */
    fun governor(): StreamGovernor {
        val ceiling = ThermalCeiling.capFor(COOL, ceilingFps)
        return StreamGovernor(
            AutoFpsController.plan(VideoLoop.Settings().startFps, ceiling), 0, StreamGovernor.WINDOW_MS, bitrateBps, keyframeSec, lightScreenFps = lightScreenFps,
        )
    }
}

private const val COOL = 0

/** How the dash acknowledges frames: the real one almost never does; some acknowledge the keyframes only. */
internal enum class Acks { NONE, KEYFRAMES, EVERY_FRAME }

/** The route as `LiveSides.navSample` hands it: the two profile switches (off until the settings are loaded), the distance to the maneuver and the speed (null: no data). */
internal data class Route(val meters: Int?, val kmh: Double?, val switchesOn: Boolean = true)

/** What surrounds the stream during one poll of 250 ms. */
internal data class Scene(
    /** Android Auto sends new pictures (the map moves); false: a still screen, the compositor only repeats. */
    val moving: Boolean = true,
    /** New pictures per compositor tick, in thousandths: under 1000 the source is slower than the pace. */
    val sourcePermille: Int = 1000,
    /** Frames the encoder gives out per composed frame, in thousandths. */
    val deliveryPermille: Int = 1000,
    val bytesPerFrame: Int = 5_000,
    val confirmed: Boolean = true,
    val acks: Acks = Acks.NONE,
    /** PowerManager's thermal status (0 none … 4 critical). */
    val thermalStatus: Int = 0,
    /** `thermalOk` false whatever the status: the real ceiling of 4 never gets there, but both governors must answer it alike. */
    val overCeiling: Boolean = false,
    /** Android Auto shows its app menu (a light screen). */
    val menu: Boolean = false,
    val route: Route? = null,
)

internal sealed interface Step {
    /** One poll, 250 ms after the one before. */
    data class Poll(val scene: Scene) : Step

    /** `VideoLoop.restartStream` or a withheld keyframe: `syncRequested(reason)`. */
    data class Sync(val reason: String) : Step

    /** The encoder failed: `rebuildAction()` is read and a new encoder takes over. */
    data object EncoderFailed : Step

    /** A line of the script, for whoever reads the trace. */
    data class Note(val text: String) : Step
}

/**
 * The governor on the bench: each poll is built the way `VideoLoop.poll` builds it, from a [Scene] and from what a compositor and an
 * encoder would do with the pace the governor itself set (a tick every `activeMs` while pictures come, a repeat every `idleMs` when not;
 * a keyframe after each request, and the encoder's own every `encoderFps × encoderKeyframeSec` frames).
 */
internal class GovernorBench(val governor: StreamGovernor, private val config: GovernorConfig) {
    var nowMs = 0L
        private set
    private var pace = DashCompositor.Pace(POLL_MS, POLL_MS)
    private var tickCarryMs = 0L
    private var sourceCarry = 0
    private var deliveryCarry = 0
    private var acks = 0L
    private var keyframeDue = false
    private var framesSinceKey = 0

    fun start(): List<Action> = governor.start(nowMs).also(::obey)

    fun poll(scene: Scene): List<Action> {
        nowMs += POLL_MS
        tickCarryMs += POLL_MS
        val interval = if (scene.moving) pace.activeMs else pace.idleMs
        val ticks = (tickCarryMs / interval).toInt()
        tickCarryMs %= interval
        val drawn = if (scene.moving) whole(ticks * scene.sourcePermille, sourceCarry).also { sourceCarry = it.second }.first else 0
        val repeated = if (scene.moving) 0 else ticks
        val framesOut = whole((drawn + repeated) * scene.deliveryPermille, deliveryCarry).also { deliveryCarry = it.second }.first
        val keyframe = framesOut > 0 && (keyframeDue || framesSinceKey + framesOut >= governor.encoderFps * governor.encoderKeyframeSec)
        framesSinceKey = if (keyframe) 0 else framesSinceKey + framesOut
        if (keyframe) keyframeDue = false
        val keyBytes = if (keyframe) scene.bytesPerFrame * KEYFRAME_TIMES else 0
        acks += when (scene.acks) {
            Acks.NONE -> 0
            Acks.KEYFRAMES -> if (keyframe) 1 else 0
            Acks.EVERY_FRAME -> framesOut
        }
        val actions = governor.onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(
                    sourceFrames = if (scene.moving) ticks else 0, drawn = drawn, repeated = repeated, composeNs = (drawn + repeated) * COMPOSE_NS, wakeups = if (scene.moving) ticks else 0,
                ),
                framesOut = framesOut,
                bytesOut = framesOut.toLong() * scene.bytesPerFrame + if (keyframe) keyBytes - scene.bytesPerFrame else 0,
                keyframes = if (keyframe) 1 else 0, keyBytesMax = keyBytes, keyPacketsMax = if (keyframe) keyBytes / RTP_PAYLOAD + 1 else 0,
                lateFrames = 0, encodeNs = framesOut * ENCODE_NS,
                dashAcks = acks, firstFrameConfirmed = scene.confirmed,
                // The two lines below are VideoLoop.poll's own.
                thermalOk = !scene.overCeiling && ThermalCeiling.allows(scene.thermalStatus, governor.profileFps, config.ceilingFps),
                link = LinkSample(scene.route?.kmh, WIFI_DBM, WIFI_MBPS),
                navSample = scene.route?.let { StreamGovernor.NavSample(it.switchesOn, it.switchesOn, it.meters, it.kmh, NavProfileMode.PESSIMISTA) },
                lightScreen = VideoLoop.lightScreenOn(config.lightScreenFps, scene.thermalStatus) { scene.menu },
            ),
        )
        obey(actions)
        return actions
    }

    fun syncRequested(reason: String) {
        governor.syncRequested(reason, nowMs)
        keyframeDue = true
    }

    /** The encoder failed: what `VideoLoop` would rebuild it with. The new one opens with a keyframe. */
    fun encoderFailed(): Action.Rebuild = governor.rebuildAction().also { keyframeDue = true }

    private fun obey(actions: List<Action>) {
        for (action in actions) {
            when (action) {
                is Action.SetPace -> pace = action.pace
                is Action.RequestSync, is Action.Rebuild -> keyframeDue = true
                else -> Unit
            }
        }
    }

    /** [thousandths] and what was left over before, as whole frames and the new left-over. */
    private fun whole(thousandths: Int, carry: Int): Pair<Int, Int> = (thousandths + carry).let { it / PERMILLE to it % PERMILLE }

    companion object {
        const val POLL_MS = 250L
        private const val PERMILLE = 1000
        private const val KEYFRAME_TIMES = 3
        private const val RTP_PAYLOAD = 1_400
        private const val COMPOSE_NS = 2_000_000L
        private const val ENCODE_NS = 8_000_000L
        private const val WIFI_DBM = -58
        private const val WIFI_MBPS = 72
    }
}

/**
 * What the governor answered, as text: per poll, each action it gave, and the eight values `VideoLoop` reads of it whenever one of
 * them changed. A poll with no action and no change has no line (the last line says how many polls ran), so a trace stays short enough
 * to read in a review.
 *
 * The text in brackets after `auto=4fps` (the ladder's own state: `at ceiling`, `healthy 2/3`…) is written as `[*]`: the owner chose
 * (14/11/2026) that the cut replaces it with a stateless text, and it is the one thing of the line allowed to change. The number before
 * it stays in the trace.
 */
internal object GovernorTrace {
    private val BRACKET = Regex("""auto=(\d+)fps \[[^\]]*]""")
    const val WINDOW_LINE = "video fps target="

    fun record(config: GovernorConfig, steps: List<Step>, onPoll: (GovernorBench, List<Action>) -> Unit = { _, _ -> }): List<String> {
        val bench = GovernorBench(config.governor(), config)
        val out = ArrayList<String>()
        var reads = ""
        var polls = 0
        fun said(what: List<String>) {
            what.forEachIndexed { i, text -> out += if (i == 0) "%7d  %s".format(Locale.US, bench.nowMs, text) else "         $text" }
            val now = reads(bench.governor)
            if (now != reads) out += "         reads: $now"
            reads = now
        }
        said(listOf("start") + bench.start().map(::text))
        for (step in steps) {
            when (step) {
                is Step.Note -> out += "# ${step.text}"
                is Step.Sync -> {
                    bench.syncRequested(step.reason)
                    said(listOf("syncRequested(${step.reason})"))
                }
                Step.EncoderFailed -> said(listOf("encoder failed, rebuildAction(): ${text(bench.encoderFailed())}"))
                is Step.Poll -> {
                    val actions = bench.poll(step.scene)
                    polls++
                    onPoll(bench, actions)
                    said(actions.map(::text).let { if (it.isEmpty() && reads(bench.governor) != reads) listOf("(no action)") else it })
                }
            }
        }
        out += "%7d  end, %d polls".format(Locale.US, bench.nowMs, polls)
        return out
    }

    /** The eight values `VideoLoop` reads of the governor (VideoLoopReadsOfTheGovernorTest holds that list). */
    fun reads(g: StreamGovernor): String =
        "fps=${g.fps} profileFps=${g.profileFps} encoderFps=${g.encoderFps} gopFps=${g.gopFps} encoderKeyframeSec=${g.encoderKeyframeSec} " +
            "keyframeSec=${g.keyframeSec} activeBitrateBps=${g.activeBitrateBps} rebuildAction=${text(g.rebuildAction())}"

    fun text(action: Action): String = when (action) {
        is Action.SetPace -> "pace=${action.pace.activeMs}/${action.pace.idleMs}" + if (action.pace.clockSlowPercent != 0) " slow=${action.pace.clockSlowPercent}%" else ""
        is Action.SetBitrate -> "bitrate=${action.bps}"
        is Action.RequestSync -> "sync=${action.reason}"
        is Action.Rebuild -> "rebuild(fps=${action.fps} keyframeSec=${action.keyframeSec} bitrateBps=${action.bitrateBps} encoderKeyframeSec=${action.encoderKeyframeSec})"
        is Action.Log -> "log: ${withoutLadderState(action.line)}"
        is Action.Stats -> "stats=%.1ffps/%.0fkbps".format(Locale.US, action.sentFps, action.kbps)
    }

    fun withoutLadderState(line: String): String = BRACKET.replace(line) { "auto=${it.groupValues[1]}fps [*]" }

    /** The automatic rate a window's line says (the number before the bracket), or null when the line has none or more than one. */
    fun autoFpsOf(line: String): Int? = BRACKET.findAll(line).toList().singleOrNull()?.groupValues?.get(1)?.toInt()

    /** The ladder's own text of a window's line (inside the bracket). */
    fun ladderStateOf(line: String): String? = BRACKET.find(line)?.value?.substringAfter('[')?.removeSuffix("]")
}

/** A script written by hand: the scene is changed and held for some seconds. */
internal class Script(private var scene: Scene = Scene()) {
    val steps = ArrayList<Step>()

    fun note(text: String) {
        steps += Step.Note(text)
    }

    /** [seconds] of polls with the scene changed by [change]; [note] says what it is in the trace. */
    fun hold(seconds: Double, note: String, change: Scene.() -> Scene = { this }) {
        note(note)
        scene = scene.change()
        repeat((seconds * POLLS_PER_SECOND).toInt()) { steps += Step.Poll(scene) }
    }

    /** One poll per element of [values], the scene changed by it (a value that moves at every poll). */
    fun <T> perPoll(values: List<T>, note: String, change: Scene.(T) -> Scene) {
        note(note)
        for (v in values) {
            scene = scene.change(v)
            steps += Step.Poll(scene)
        }
    }

    fun sync(reason: String) {
        steps += Step.Sync(reason)
    }

    fun encoderFailed() {
        steps += Step.EncoderFailed
    }

    companion object {
        const val POLLS_PER_SECOND = 4
        fun of(start: Scene = Scene(), body: Script.() -> Unit): List<Step> = Script(start).apply(body).steps
    }
}

/** One case of the table: its golden file, what it shows, and the script. */
internal class GovernorCase(val file: String, val shows: String, val steps: List<Step>)

/**
 * The table of plan 27, section 4, layer 2: what the code says of the ride logs, one script each. The distances and speeds are round
 * numbers around the thresholds of the code (1000 m and 200 m, 55 and 65 km/h, 10 s, 7 s, 1.5 s, 2 s, 500 ms), not readings of a ride.
 */
internal object GovernorCases {
    private val slowFar = Route(meters = 2000, kmh = 40.0)
    private val slowNear = Route(meters = 500, kmh = 40.0)
    private val fastNear = Route(meters = 500, kmh = 70.0)
    private val noData = Route(meters = null, kmh = null)

    /** 50 and 70 km/h by turns, 3 s each, near a maneuver, for [seconds]. */
    private fun speedWobble(seconds: Int): List<Step> = Script.of {
        perPoll((0 until seconds * Script.POLLS_PER_SECOND).toList(), "500 m from the maneuver, 50 and 70 km/h by turns of 3 s, for $seconds s") { i ->
            copy(route = Route(meters = 500, kmh = if (i / (3 * Script.POLLS_PER_SECOND) % 2 == 0) 50.0 else 70.0))
        }
    }

    val ALL: List<GovernorCase> = listOf(
        GovernorCase(
            "01-stream-start", "the still pace and the still bitrate until the dash confirms the first frame, and sync=stream start",
            Script.of(Scene(confirmed = false)) { hold(6.0, "the map moves, the dash has confirmed nothing yet") },
        ),
        GovernorCase(
            "02-moving-no-route", "4 fps at the full bitrate, one sync=pace a second",
            Script.of { hold(12.0, "first frame confirmed, the map moves, no route") },
        ),
        GovernorCase(
            "03-still-screen", "half the bitrate 1.5 s after the last new picture, sync=idle every 2 s",
            Script.of {
                hold(3.0, "the map moves")
                hold(9.0, "the screen is still") { copy(moving = false) }
                hold(2.0, "the map moves again") { copy(moving = true) }
            },
        ),
        GovernorCase(
            "04-route-far", "2 fps and 200 kbps once the distance moved 200 m in the far zone, and no rebuild",
            Script.of {
                hold(3.0, "a route, 2000 m from the maneuver, 40 km/h") { copy(route = slowFar) }
                hold(15.0, "1790 m from the maneuver") { copy(route = slowFar.copy(meters = 1790)) }
            },
        ),
        GovernorCase(
            "05-route-near-again", "back to 4 fps under 1000 m, but only once the reduced tier was kept 10 s",
            Script.of {
                hold(1.0, "a route, 2000 m from the maneuver, 40 km/h") { copy(route = slowFar) }
                hold(13.0, "1790 m: reduced once the first tier was kept 10 s") { copy(route = slowFar.copy(meters = 1790)) }
                hold(12.0, "900 m, under 4 s after the tier turned reduced") { copy(route = slowFar.copy(meters = 900)) }
            },
        ),
        GovernorCase(
            "06-fast-near", "2 fps: the speed profile says reduced and the pessimistic mode takes it",
            Script.of { hold(8.0, "a route, 500 m from the maneuver, 70 km/h") { copy(route = fastNear) } },
        ),
        GovernorCase("07-speed-wobble", "one tier change every 10 s at most, however often the speed crosses the band", speedWobble(seconds = 90)),
        GovernorCase(
            "08-full-then-reduced-6s-later", "a full tier taken from no tier counts its 10 s too",
            Script.of {
                hold(6.0, "a route, 500 m from the maneuver, 40 km/h: full, from no tier") { copy(route = slowNear) }
                hold(8.0, "70 km/h, 6 s later") { copy(route = fastNear) }
            },
        ),
        GovernorCase(
            "09-route-data-gaps", "the tier is held 7 s without data, then falls to none (4 fps)",
            Script.of {
                hold(4.0, "a route, 500 m from the maneuver, 70 km/h") { copy(route = fastNear) }
                hold(5.0, "no distance and no speed for 5 s") { copy(route = noData) }
                hold(4.0, "the data is back") { copy(route = fastNear) }
                hold(8.0, "no distance and no speed for 8 s") { copy(route = noData) }
                hold(3.0, "the data is back") { copy(route = fastNear) }
            },
        ),
        GovernorCase(
            "10-menu-in-reduced-profile", "6 fps at the full bitrate on the app menu, back 500 ms after leaving it",
            Script.of {
                hold(4.0, "a route, 500 m from the maneuver, 70 km/h") { copy(route = fastNear) }
                hold(6.0, "the app menu of Android Auto") { copy(menu = true) }
                hold(4.0, "back on the map") { copy(menu = false) }
            },
        ),
        GovernorCase(
            "11-six-fps-for-a-minute", "one sync=pace a second at 6 fps, not 1.5",
            Script.of {
                hold(2.0, "the map moves, no route")
                hold(60.0, "the app menu of Android Auto, a minute of it") { copy(menu = true) }
            },
        ),
        GovernorCase(
            "12-menu-hot-phone", "from 'moderate' on the menu stays at the profile's pace",
            Script.of {
                hold(3.0, "the app menu, phone cool (status 0)") { copy(menu = true) }
                hold(3.0, "status 2 (moderate)") { copy(thermalStatus = 2) }
                hold(3.0, "status 3 (severe)") { copy(thermalStatus = 3) }
                hold(3.0, "status 4 (critical)") { copy(thermalStatus = 4) }
                hold(3.0, "status 1 (light)") { copy(thermalStatus = 1) }
            },
        ),
        GovernorCase(
            "13-dash-acks", "no dash= while the dash acknowledges nothing, dash= once it does, the pace the same",
            Script.of {
                hold(11.0, "the dash acknowledges no frame")
                hold(11.0, "the dash acknowledges the keyframes only") { copy(acks = Acks.KEYFRAMES) }
                hold(6.0, "the dash acknowledges every frame") { copy(acks = Acks.EVERY_FRAME) }
            },
        ),
        GovernorCase(
            "14-encoder-short", "the pace stays 4 (the floor) with 3 frames of 4 coming out; only the bracket would say it",
            Script.of {
                hold(6.0, "the map moves, every frame comes out")
                hold(21.0, "the encoder gives out 3 frames of 4") { copy(deliveryPermille = 750) }
            },
        ),
        GovernorCase(
            "15-encoder-failed", "the rebuild carries the bitrate applied now (still, reduced), not the full one",
            Script.of {
                hold(2.0, "the map moves, no route")
                hold(3.0, "the screen is still") { copy(moving = false) }
                encoderFailed()
                hold(2.0, "a route, 500 m from the maneuver, 70 km/h, the map moves") { copy(moving = true, route = fastNear) }
                encoderFailed()
                hold(3.0, "the screen is still, in the reduced profile") { copy(moving = false) }
                encoderFailed()
                hold(2.0, "the map moves") { copy(moving = true) }
            },
        ),
        GovernorCase(
            "16-stream-restart-and-withheld", "a keyframe asked for by the loop counts in the sync= of the window's line",
            Script.of {
                hold(2.0, "the map moves, no route")
                sync("the dash spoke again after 3 s of silence")
                hold(2.0, "after the stream restart")
                sync("withheld")
                hold(3.0, "after the withheld keyframe")
            },
        ),
        GovernorCase(
            "17-settings-not-loaded-yet", "no rebuild when the two profile switches come on with the settings",
            Script.of {
                hold(3.0, "a route far and fast, the settings not loaded: both switches off") { copy(route = Route(meters = 2000, kmh = 70.0, switchesOn = false)) }
                hold(4.0, "the settings are loaded: both switches on") { copy(route = Route(meters = 2000, kmh = 70.0)) }
            },
        ),
    )

    /** Case 7 as the log had it, 11 minutes: too long to read, so it is kept as a digest with the seeded sequences. */
    val SPEED_WOBBLE_11_MIN: List<Step> = speedWobble(seconds = 660)
}

/**
 * Sequences drawn from a fixed seed (the same every run, `java.util.Random`): stretches of 1 to 20 s, not single polls. Each stretch
 * changes a few things of the scene: motion and stillness; 50 % to 100 % of the frames coming out; a slower source; the bytes of a
 * frame; the three ways a dash acknowledges; the first frame confirmed, lost and back; a route on and off, with distances that shrink
 * and jump, speeds around 55 and 65 km/h and gaps in the data of 1 to 12 s; the app menu blinking; the phone's heat, and a poll over
 * the heat ceiling (which the real ceiling of 4 never reaches); and, now and then, a stream restart, a withheld keyframe and an
 * encoder that failed.
 */
internal object GovernorSequences {
    /** 10 minutes of stream. */
    const val POLLS = 2_400
    private const val SALT = 27_000L

    fun of(seed: Int, polls: Int = POLLS): List<Step> = Drawn(Seeded.random(SALT + seed)).steps(polls)

    private class Drawn(private val rnd: Random) {
        private var scene = Scene()
        private var decimeters = 0
        private var kmhTenths = 0
        private var gapPolls = 0
        private var blinkEvery = 0

        fun steps(polls: Int): List<Step> {
            val out = ArrayList<Step>()
            var done = 0
            while (done < polls) {
                val stretch = minOf(polls - done, (1 + rnd.nextInt(MAX_STRETCH_S)) * Script.POLLS_PER_SECOND)
                repeat(1 + rnd.nextInt(MAX_CHANGES)) { change() }
                out += Step.Note("stretch of ${stretch / Script.POLLS_PER_SECOND} s: ${describe()}")
                repeat(stretch) { i ->
                    event()?.let { out += it }
                    out += Step.Poll(sceneAt(i))
                }
                done += stretch
            }
            return out
        }

        private fun describe(): String = scene.copy(route = scene.route?.copy(meters = decimeters / DM, kmh = kmhTenths / TENTHS)).toString() +
            (if (blinkEvery > 0) ", the menu blinks every $blinkEvery polls" else "") + if (gapPolls > 0) ", no route data for $gapPolls polls" else ""

        @Suppress("CyclomaticComplexMethod") // one branch per thing a stretch can change
        private fun change() {
            when (rnd.nextInt(CHANGES)) {
                0 -> scene = scene.copy(moving = rnd.nextInt(PERCENT) < MOVING_PERCENT)
                1 -> scene = scene.copy(deliveryPermille = if (rnd.nextBoolean()) FULL else HALF + rnd.nextInt(HALF + 1))
                2 -> scene = scene.copy(sourcePermille = if (rnd.nextInt(PERCENT) < MOVING_PERCENT) FULL else SLOW_SOURCE + rnd.nextInt(FULL - SLOW_SOURCE))
                3 -> scene = scene.copy(bytesPerFrame = MIN_FRAME_BYTES + rnd.nextInt(MAX_FRAME_BYTES - MIN_FRAME_BYTES))
                4 -> scene = scene.copy(acks = Acks.entries[rnd.nextInt(Acks.entries.size)])
                5 -> scene = scene.copy(confirmed = rnd.nextInt(PERCENT) < CONFIRMED_PERCENT)
                6 -> route()
                7 -> gapPolls = if (scene.route != null) (1 + rnd.nextInt(MAX_GAP_S)) * Script.POLLS_PER_SECOND else 0
                8 -> {
                    blinkEvery = if (rnd.nextBoolean()) 0 else 1 + rnd.nextInt(MAX_BLINK_POLLS)
                    scene = scene.copy(menu = rnd.nextBoolean())
                }
                9 -> scene = scene.copy(thermalStatus = if (rnd.nextBoolean()) 0 else rnd.nextInt(HOTTEST + 1))
                else -> scene = scene.copy(overCeiling = rnd.nextInt(PERCENT) < OVER_CEILING_PERCENT)
            }
        }

        private fun route() {
            val kind = rnd.nextInt(PERCENT)
            scene = when {
                kind < NO_ROUTE_PERCENT -> scene.copy(route = null)
                else -> {
                    decimeters = (MIN_METERS + rnd.nextInt(MAX_METERS - MIN_METERS)) * DM
                    kmhTenths = (MIN_KMH + rnd.nextInt(MAX_KMH - MIN_KMH)) * TENTHS.toInt()
                    scene.copy(route = Route(0, 0.0, switchesOn = kind >= NO_ROUTE_PERCENT + SWITCHES_OFF_PERCENT))
                }
            }
            gapPolls = 0
        }

        /** The scene of the stretch at its poll [i]: the route moves on, the speed wobbles, the menu blinks. */
        private fun sceneAt(i: Int): Scene {
            val route = scene.route?.let { r ->
                kmhTenths = (kmhTenths + rnd.nextInt(2 * SPEED_STEP + 1) - SPEED_STEP).coerceIn(0, TOP_KMH * TENTHS.toInt())
                decimeters -= kmhTenths * METERS_PER_POLL_NUM / METERS_PER_POLL_DEN
                // The maneuver was passed: the next one is somewhere ahead (the distance jumps).
                if (decimeters <= 0) decimeters = (MIN_METERS + rnd.nextInt(MAX_METERS - MIN_METERS)) * DM
                if (gapPolls > 0) {
                    gapPolls--
                    r.copy(meters = null, kmh = null)
                } else {
                    r.copy(meters = decimeters / DM, kmh = kmhTenths / TENTHS)
                }
            }
            val menu = if (blinkEvery > 0) (i / blinkEvery) % 2 == 0 else scene.menu
            return scene.copy(route = route, menu = menu)
        }

        private fun event(): Step? = when (rnd.nextInt(EVENT_ONE_IN)) {
            0 -> Step.Sync("the dash spoke again after ${1 + rnd.nextInt(MAX_GAP_S)} s of silence")
            1 -> Step.Sync("withheld")
            2 -> Step.EncoderFailed
            else -> null
        }

        private companion object {
            const val MAX_STRETCH_S = 20
            const val MAX_CHANGES = 3
            const val CHANGES = 11
            const val PERCENT = 100
            const val MOVING_PERCENT = 75
            const val CONFIRMED_PERCENT = 85
            const val OVER_CEILING_PERCENT = 30
            const val NO_ROUTE_PERCENT = 25
            const val SWITCHES_OFF_PERCENT = 10
            const val FULL = 1000
            const val HALF = 500
            const val SLOW_SOURCE = 600
            const val MIN_FRAME_BYTES = 1_500
            const val MAX_FRAME_BYTES = 40_000
            const val MAX_GAP_S = 12
            const val MAX_BLINK_POLLS = 6
            const val HOTTEST = 4
            const val MIN_METERS = 150
            const val MAX_METERS = 4_000
            const val MIN_KMH = 45
            const val MAX_KMH = 80
            const val TOP_KMH = 120
            const val DM = 10
            const val TENTHS = 10.0
            const val SPEED_STEP = 25

            // Decimeters a poll of 250 ms covers at a speed in tenths of km/h: v / 10 / 3.6 × 0.25 × 10 = v × 25 / 360.
            const val METERS_PER_POLL_NUM = 25
            const val METERS_PER_POLL_DEN = 360

            /** One event in about 30 s of polls, each kind. */
            const val EVENT_ONE_IN = 360
        }
    }
}

/** The stream's rates the real configuration can show, for the tests that check them at every poll. */
internal object RealRates {
    val PACES = setOf(DashConfig.PROFILE_REDUCED_FPS, DashConfig.PROFILE_FULL_FPS, DashConfig.LIGHT_SCREEN_FPS)
}
