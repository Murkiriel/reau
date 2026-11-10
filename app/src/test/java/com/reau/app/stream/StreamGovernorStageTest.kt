package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.util.StageClock
import com.reau.app.util.StageStats
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fields the `video fps` line got on 03/11/2026 (the worst and the 95th percentile of the compose and the encode, the cost of the picture
 * looks and of the latch of each new picture, and how many pictures woke the compositor) come after all the original ones, which keep their
 * place and format, so `scripts/registro/ler_registro.py` reads old and new lines alike.
 */
class StreamGovernorStageTest {
    private var nowMs = 0L
    private val ms = 1_000_000L

    private fun governor() = StreamGovernor(AutoFpsController.Plan(4, 4), bitrateCeilingBps = 400_000, fixedKeyframeSec = 1)

    private fun stats(vararg ns: Long): StageStats = StageClock().also { c -> ns.forEach(c::add) }.take()

    private fun StreamGovernor.poll(
        compose: StageStats = StageStats.EMPTY,
        look: StageStats = StageStats.EMPTY,
        latch: StageStats = StageStats.EMPTY,
        wakeups: Int = 0,
        encode: StageStats = StageStats.EMPTY,
    ): List<Action> {
        nowMs += 250L
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(
                    sourceFrames = wakeups, drawn = 1, repeated = 0, composeNs = 3 * ms,
                    compose = compose, look = look, latch = latch, wakeups = wakeups,
                ),
                framesOut = 1, bytesOut = 1_000L, lateFrames = 0, encodeNs = 14 * ms, dashAcks = 0, firstFrameConfirmed = true, encode = encode,
            ),
        )
    }

    /** The one line of a window of 20 polls; poll `i` carries `perPoll(i)`. */
    private fun window(perPoll: (Int) -> Poll): String {
        val g = governor()
        g.start(nowMs)
        g.poll() // opens the window (its frames are not counted)
        return (0 until 20).flatMap { i -> perPoll(i).let { g.poll(it.compose, it.look, it.latch, it.wakeups, it.encode) } }
            .filterIsInstance<Action.Log>().map { it.line }.single { it.startsWith("video fps") }
    }

    private class Poll(
        val compose: StageStats = StageStats.EMPTY,
        val look: StageStats = StageStats.EMPTY,
        val latch: StageStats = StageStats.EMPTY,
        val wakeups: Int = 0,
        val encode: StageStats = StageStats.EMPTY,
    )

    @Test
    fun `the window's worst compose and encode, their percentile, the looks, the latch and the wakeups end the line`() {
        val line = window { i ->
            Poll(
                compose = stats(3 * ms, if (i == 7) 7_600_000L else 3 * ms),
                encode = stats(if (i == 3) 19_200_000L else 14 * ms),
                look = if (i % 10 == 0) stats(900_000L, 1_400_000L) else StageStats.EMPTY,
                latch = stats(*LongArray(7) { 200_000L }),
                wakeups = 7,
            )
        }
        assertTrue(line, line.endsWith(" compose_max=7.6ms compose_p95=3.5ms encode_max=19.2ms encode_p95=14.5ms look=4/1.15/1.40ms latch=140/0.20/0.20ms wake=140(28.0/s)"))
    }

    @Test
    fun `a window without the new measurements says so, with the original fields untouched`() {
        val line = window { Poll() }
        assertTrue(line, line.endsWith(" compose_max=- compose_p95=- encode_max=- encode_p95=- look=0 latch=0 wake=0(0.0/s)"))
        // The original line, field by field, up to the bracket that closed it before.
        assertTrue(line, line.startsWith("video fps target=4 sent="))
        val original = line.substringBefore(" compose_max=")
        assertTrue(original, original.contains(" source=0 drawn=20 repeated=0 compose=3.0ms encode=14.0ms late=0 tickwait=- full="))
        assertTrue(original, original.endsWith("]"))
        assertEquals("no field of the original line comes after the new ones", 1, line.split(" compose_max=").size - 1)
    }

    @Test
    fun `the counts start again with each window`() {
        val g = governor()
        g.start(nowMs)
        g.poll()
        val first = (0 until 20).flatMap { g.poll(compose = stats(30 * ms), wakeups = 5) }.filterIsInstance<Action.Log>().single { it.line.startsWith("video fps") }.line
        val second = (0 until 20).flatMap { g.poll(compose = stats(2 * ms), wakeups = 1) }.filterIsInstance<Action.Log>().single { it.line.startsWith("video fps") }.line
        assertTrue(first, " compose_max=30.0ms " in first && first.endsWith("wake=100(20.0/s)"))
        assertTrue(second, " compose_max=2.0ms " in second && second.endsWith("wake=20(4.0/s)"))
    }
}
