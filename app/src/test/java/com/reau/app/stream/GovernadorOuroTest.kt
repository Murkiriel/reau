package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Plan 27, layer 2: golden traces of the stream governor. Each case of [GovernorCases] and each seeded sequence of
 * [GovernorSequences] runs on the bench ([GovernorBench]) with the two real configurations (400 kbps, and 200 kbps with the adaptive
 * bitrate experiment), and what the governor answers at every poll of 250 ms (its actions, and the eight values `VideoLoop` reads of
 * it, [GovernorTrace]) must be, to the letter, what the files in `app/src/test/resources/governador/` say:
 *
 * - `NN-name.txt`: one case of the table, readable, both configurations in the one file;
 * - `digests.txt`: the SHA-256 of the traces that are too long to read (the seeded sequences, and case 7 at its 11 minutes).
 *
 * THE GOLDEN FILES WERE RECORDED FROM THE CODE BEFORE THE FPS LADDER WAS CUT (step G0, 14/11/2026) AND ARE ONLY READ FROM THEN ON.
 * The commit that cuts the ladder (step G1), and the one that cuts the automatic keyframe (G2), must pass with these files untouched:
 * that the files did not move is what the proof is, and `git diff` of those commits shows it. Recording them again there, "to pass",
 * would be cheating. The one thing of a trace that is allowed to change with the cut is the text in brackets after `auto=4fps`, and the
 * trace already holds it as `[*]`.
 *
 * To record (only when a case is added, or the bench itself changes, on code whose behaviour is known to be the wanted one; say so in the
 * commit, and never together with a change of `app/src/main`):
 *
 *     REAU_GRAVAR_OURO=1 ./gradlew --offline :app:testDebugUnitTest --tests '*.GovernadorOuroTest' --rerun
 *
 * The run writes the files and fails on purpose (a recording run proves nothing); run it again without the variable.
 */
class GovernadorOuroTest {
    private val dir = File("src/test/resources/governador")
    private val recording = System.getenv("REAU_GRAVAR_OURO") == "1"

    private fun trace(config: GovernorConfig, steps: List<Step>): List<String> = GovernorTrace.record(config, steps)

    private fun textOf(case: GovernorCase): String = buildString {
        append("# Golden trace of the stream governor (plan 27, GovernadorOuroTest). Recorded from the code, never edited by hand.\n")
        append("# Case ${case.file}: ${case.shows}.\n")
        append("# A line is a poll that had an action (the time in ms, then one action per line); `reads:` are the values the video loop reads, when one changed.\n")
        for (config in GovernorConfig.REAL) {
            append("\n== ${config.name} ==\n")
            trace(config, case.steps).forEach { append(it).append('\n') }
        }
    }

    /** What `digests.txt` holds: a name and the SHA-256 of that trace. */
    private fun digests(): List<String> {
        val long = GovernorConfig.REAL.map { "case 07 for 11 minutes, ${it.name}" to trace(it, GovernorCases.SPEED_WOBBLE_11_MIN) }
        val seeded = (GovernorConfig.REAL.flatMap { c -> (1..REAL_SEEDS).map { c to it } } +
            listOf(GovernorConfig.NO_LIGHT_SCREEN, GovernorConfig.KEYFRAME_2S).flatMap { c -> (1..OTHER_SEEDS).map { c to it } })
            .map { (config, seed) -> "seed $seed, ${GovernorSequences.POLLS} polls, ${config.name}" to trace(config, GovernorSequences.of(seed)) }
        return (long + seeded).map { (name, lines) -> "${sha256(lines.joinToString("\n"))}  $name" }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Compares [actual] with the golden [name], or writes it when recording. On a difference the actual text is left in `build/governador/` to compare. */
    private fun held(name: String, actual: String): String? {
        val golden = File(dir, name)
        if (recording) {
            dir.mkdirs()
            golden.writeText(actual)
            return null
        }
        if (!golden.isFile) return "$name: no golden file"
        val wanted = golden.readText()
        if (wanted == actual) return null
        val out = File("build/governador").apply { mkdirs() }.resolve(name).also { it.writeText(actual) }
        val (w, a) = wanted.lines() to actual.lines()
        val at = (0 until maxOf(w.size, a.size)).first { w.getOrNull(it) != a.getOrNull(it) }
        return "$name differs at line ${at + 1} (the whole trace of this run is in app/${out.path}):\n  golden: ${w.getOrNull(at)}\n  now:    ${a.getOrNull(at)}"
    }

    private fun judged(differences: List<String>) {
        if (recording) fail("the golden files were RECORDED by this run (REAU_GRAVAR_OURO=1), nothing was compared: run again without the variable")
        assertTrue(
            "the governor no longer answers as the golden traces say. If this is the cut of the ladder, the code is wrong, not the files:\n" +
                differences.joinToString("\n"),
            differences.isEmpty(),
        )
    }

    @Test
    fun `each case of the table gives the trace its golden file holds, at 400 and at 200 kbps`() {
        judged(GovernorCases.ALL.mapNotNull { held("${it.file}.txt", textOf(it)) })
    }

    @Test
    fun `the seeded sequences and the 11 minutes of case 7 give the digests the golden file holds`() {
        val header = "# SHA-256 of the traces too long to read (plan 27, GovernadorOuroTest). Recorded from the code, never edited by hand.\n"
        judged(listOfNotNull(held(DIGESTS, header + digests().joinToString("\n", postfix = "\n"))))
    }

    @Test
    fun `the folder holds the golden files of this table and nothing else`() {
        if (recording) return
        val wanted = (GovernorCases.ALL.map { "${it.file}.txt" } + DIGESTS).sorted()
        assertEquals(wanted, dir.list().orEmpty().sorted())
        assertEquals("the table of the plan", CASES, GovernorCases.ALL.size)
    }

    @Test
    fun `a trace hides the ladder's own text and nothing else, and the rate before it is 4 in every window`() {
        val line = "video fps target=4 sent=4.0 kf=1s idr=5(max 15000B/11pkt) sync=5[pace:5] auto=4fps [healthy 2/3] compose_max=- wake=20(4.0/s)"
        assertEquals(
            "video fps target=4 sent=4.0 kf=1s idr=5(max 15000B/11pkt) sync=5[pace:5] auto=4fps [*] compose_max=- wake=20(4.0/s)",
            GovernorTrace.withoutLadderState(line),
        )
        assertEquals("healthy 2/3", GovernorTrace.ladderStateOf(line))
        var windows = 0
        for (config in GovernorConfig.REAL) {
            for (case in GovernorCases.ALL) {
                for (window in trace(config, case.steps).filter { GovernorTrace.WINDOW_LINE in it }) {
                    windows++
                    assertEquals("${case.file}, ${config.name}: $window", 4, GovernorTrace.autoFpsOf(window))
                    assertTrue("${case.file}, ${config.name}: $window", window.contains("auto=4fps [*]") && " kf=1s " in window)
                }
            }
        }
        assertTrue("the cases closed windows: $windows", windows > 2 * CASES)
    }

    @Test
    fun `the table really shows the three paces, both bitrates, the three kinds of keyframe request and a rebuild after a failure`() {
        val all = GovernorCases.ALL.flatMap { case -> GovernorConfig.REAL.flatMap { trace(it, case.steps) } }.joinToString("\n")
        for (shown in listOf(
            "pace=500/500", "pace=250/500", "pace=167/334", "bitrate=400000", "bitrate=200000", "bitrate=100000",
            "sync=stream start", "sync=pace", "sync=idle", "syncRequested(withheld)", "encoder failed, rebuildAction(): rebuild(fps=4 keyframeSec=1 bitrateBps=100000 encoderKeyframeSec=2)",
            "nav profile: none -> FULL", "nav profile: FULL -> REDUCED", "nav profile: REDUCED -> FULL", "nav profile: REDUCED -> none (no data for 7 s)",
            "light screen: pace up to 6 fps", "light screen: pace back to the profile's 2 fps", " dash=", "thermal=OK",
            "fps=2 profileFps=2 encoderFps=4 gopFps=6 encoderKeyframeSec=2 keyframeSec=1", "fps=6 profileFps=2", "fps=6 profileFps=4",
        )) {
            assertTrue("no trace shows `$shown`", shown in all)
        }
        assertTrue("no case rebuilds the encoder in a poll", GovernorCases.ALL.none { case -> GovernorConfig.REAL.any { c -> trace(c, case.steps).any { it.trim().startsWith("rebuild(") } } })
    }

    private companion object {
        const val DIGESTS = "digests.txt"
        const val CASES = 17
        const val REAL_SEEDS = 24
        const val OTHER_SEEDS = 8
    }
}
