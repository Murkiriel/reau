package com.reau.app.stream

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.fuzz.Seeded
import com.reau.app.settings.REauSettings
import com.reau.app.stream.StreamGovernor.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 27, layer 1: with the settings the app really runs with, the fps ladder (`AutoFpsController`) cannot be reached. It never
 * climbs and never comes down, because the ceiling the settings fix (4) is also the ladder's floor. These tests run on the code as it
 * is BEFORE the ladder is cut, and are what justifies cutting it: what goes away does not run.
 *
 * After the cut (step G1) the first two tests stay as they are (they hold the fixed settings and the heat ceiling); the two that name
 * `AutoFpsController` go to the archive with it, and the last one keeps its checks on the governor.
 */
class EscadaInalcancavelTest {
    private val extremes = listOf(0, 2, 6, 16, 30, 99)

    @Test
    fun `whatever was stored, the settings in use ask for 4 fps, 400 kbps constant, a keyframe a second and both profiles, pessimistic`() {
        val stored = listOf(REauSettings()) + extremes.flatMap { v ->
            listOf(
                REauSettings(fpsCeiling = v),
                REauSettings(keyframeSec = v),
                REauSettings(streamBitrateBps = v * 1000),
                REauSettings(clockSlowPercent = v),
                REauSettings(
                    fpsCeiling = v, keyframeSec = v, streamBitrateBps = v * 100_000, clockSlowPercent = v, bitrateMode = BitrateMode.VBR,
                    maneuverFpsProfile = false, speedFpsProfile = false, maneuverData = false, navProfileMode = NavProfileMode.OTIMISTA,
                ),
            )
        }
        for (settings in stored) {
            for (debug in listOf(true, false)) {
                val s = settings.sanitized(debugBuild = debug)
                val said = "stored $settings"
                assertEquals(said, 4, s.fpsCeiling)
                assertEquals(said, 1, s.keyframeSec)
                assertEquals(said, BitrateMode.CBR, s.bitrateMode)
                assertEquals(said, 400_000, s.streamBitrateBps)
                assertEquals(said, 0, s.clockSlowPercent)
                assertTrue(said, s.maneuverFpsProfile && s.speedFpsProfile && s.maneuverData)
                assertEquals(said, NavProfileMode.PESSIMISTA, s.navProfileMode)

                val stream = VideoLoop.Settings().withRider(s)
                assertEquals(said, 4, stream.ceilingFps)
                assertEquals(said, 1, stream.keyframeSec)
                assertEquals(said, 400_000, stream.bitrateBps)
                assertEquals(said, 6, stream.lightScreenFps)
                assertEquals("the adaptive bitrate experiment is the one other bitrate", 200_000, VideoLoop.Settings().withRider(s.copy(experimentAdaptiveBitrate = true)).bitrateBps)
            }
        }
    }

    @Test
    fun `the stored settings pass through sanitized on the way in and on the way out`() {
        val store = File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText()
        val load = store.substringAfter("fun load(): REauSettings {").substringBefore("\n    }\n")
        assertTrue("SettingsStore.load() must end with .sanitized()", load.trimEnd().endsWith(".sanitized()"))
        assertTrue("SettingsStore.save() must store settings.sanitized()", Regex("""fun save\(settings: REauSettings\) \{\s*val s = settings\.sanitized\(\)""").containsMatchIn(store))
    }

    @Test
    fun `at a ceiling of 4 no heat lowers the ceiling, the plan is 4 of 4, and both paces are always within it`() {
        for (status in -1..6) {
            assertEquals("status $status", 4, ThermalCeiling.capFor(status, 4))
            assertEquals("status $status", AutoFpsController.Plan(4, 4), AutoFpsController.plan(DashConfig.AUTO_FPS_START, ThermalCeiling.capFor(status, 4)))
            assertTrue("2 fps at status $status", ThermalCeiling.allows(status, 2, 4))
            assertTrue("4 fps at status $status", ThermalCeiling.allows(status, 4, 4))
            assertFalse("the start of a stream never says the heat capped it", VideoLoop.thermalCapped(ThermalCeiling.capFor(status, 4), 4))
        }
    }

    @Test
    fun `the ladder at 4 of 4 never moves, whatever 100 000 windows say`() {
        val rnd = Seeded.random(salt = 27_001)
        val ladder = AutoFpsController(4, 4)
        var nowMs = 0L
        val reasons = HashSet<String>()
        repeat(WINDOWS) { round ->
            nowMs += StreamGovernor.WINDOW_MS
            val sent = rnd.nextInt(81) / 10.0
            val window = AutoFpsController.Window(
                targetFps = listOf(2, 4, 6)[rnd.nextInt(3)],
                sentFps = sent,
                decodedFps = when (rnd.nextInt(3)) {
                    0 -> null
                    1 -> sent * rnd.nextInt(30) / 100.0
                    else -> sent
                },
                kbps = rnd.nextInt(2001).toDouble(),
                allActive = rnd.nextInt(4) != 0,
                thermalOk = rnd.nextInt(4) != 0,
            )
            val decision = ladder.onWindow(window, nowMs)
            val said = "round $round, $window: $decision"
            assertFalse(said, decision.changed)
            assertEquals(said, 4, decision.fps)
            assertEquals(said, 4, ladder.currentFps)
            assertFalse(said, decision.reason.startsWith("up:") || decision.reason.startsWith("down:"))
            reasons += decision.reason.substringBefore(' ')
        }
        // The windows were not all of one kind: the ladder judged them, healthy and short, and held every time.
        assertTrue("reasons seen: $reasons", reasons.containsAll(listOf("at", "healthy", "short", "idle", "window", "settling")))
    }

    @Test
    fun `the governor with the real settings never asks for a rebuild in a poll, and its rates never leave 2, 4 and 6`() {
        var windows = 0
        val paces = HashSet<Int>()
        val states = HashSet<String>()
        for (config in GovernorConfig.REAL) {
            for (seed in 1..SEEDS) {
                var poll = 0
                GovernorTrace.record(config, GovernorSequences.of(seed)) { bench, actions ->
                    poll++
                    val g = bench.governor
                    val said = "${config.name}, seed $seed, poll $poll (${bench.nowMs} ms): $actions"
                    assertTrue(said, actions.none { it is Action.Rebuild })
                    assertEquals(said, 4, g.encoderFps)
                    assertTrue(said, g.fps in RealRates.PACES && g.profileFps in setOf(2, 4))
                    paces += g.fps
                    assertEquals(said, 1, g.keyframeSec)
                    assertEquals(said, 2, g.encoderKeyframeSec)
                    assertEquals(said, 6, g.gopFps)
                    assertEquals(said, config.bitrateBps, g.activeBitrateBps)
                    for (line in actions.filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith(GovernorTrace.WINDOW_LINE) }) {
                        windows++
                        assertEquals(said, 4, GovernorTrace.autoFpsOf(line))
                        val state = GovernorTrace.ladderStateOf(line).orEmpty()
                        assertFalse(said, state.startsWith("up:") || state.startsWith("down:"))
                        states += state.substringBefore(' ')
                        assertTrue(said, " kf=1s " in line)
                    }
                }
            }
        }
        assertTrue("the sequences closed windows: $windows", windows > SEEDS * 100)
        assertEquals("the sequences ran at every pace the stream has", RealRates.PACES, paces)
        // The ladder was asked, and held: at its ceiling, at its floor, counting healthy and short windows, and looking away from the windows at 2 and 6 fps.
        assertTrue("ladder states seen: $states", states.containsAll(listOf("at", "healthy", "short", "idle", "window")))
    }

    private companion object {
        const val WINDOWS = 100_000
        const val SEEDS = 40
    }
}
