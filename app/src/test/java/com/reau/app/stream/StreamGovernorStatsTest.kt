package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stats text on the dash's video is made again every second, not every 5 s window. */
class StreamGovernorStatsTest {
    private var nowMs = 0L

    private fun governor() = StreamGovernor(AutoFpsController.Plan(4, 4, null), bitrateCeilingBps = 200_000, fixedKeyframeSec = 1)

    private fun StreamGovernor.poll(frames: Int, bytes: Long): List<Action> {
        nowMs += 250L
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs, compositor = DashCompositor.Stats(frames, frames, 0, 0L), framesOut = frames, bytesOut = bytes,
                lateFrames = 0, encodeNs = 0L, dashAcks = 0, firstFrameConfirmed = true,
            ),
        )
    }

    @Test
    fun `a measurement comes every second with the frames and bits of that second`() {
        val g = governor()
        g.start(nowMs)
        assertEquals("the first poll only starts counting", emptyList<Action>(), g.poll(1, 5_000).filterIsInstance<Action.Stats>())
        // four polls (one second) of 1 frame and 3000 bytes each: 4 fps, 96 kbps
        val seen = (1..4).flatMap { g.poll(1, 3_000) }.filterIsInstance<Action.Stats>()
        assertEquals(1, seen.size)
        assertEquals(4.0, seen.single().sentFps, 0.001)
        assertEquals(96.0, seen.single().kbps, 0.001)
    }

    @Test
    fun `the next second counts only its own frames`() {
        val g = governor()
        g.start(nowMs)
        g.poll(0, 0)
        (1..4).forEach { g.poll(1, 3_000) }
        val second = (1..4).flatMap { g.poll(if (it == 1) 2 else 0, if (it == 1) 1_000L else 0L) }.filterIsInstance<Action.Stats>().single()
        assertEquals(2.0, second.sentFps, 0.001)
        assertEquals(8.0, second.kbps, 0.001)
    }

    @Test
    fun `a new stream starts counting again`() {
        val g = governor()
        g.start(nowMs)
        g.poll(0, 0)
        (1..3).forEach { g.poll(1, 3_000) }
        g.start(nowMs)
        assertTrue(g.poll(1, 3_000).none { it is Action.Stats })
        assertTrue((1..3).flatMap { g.poll(1, 3_000) }.none { it is Action.Stats })
    }
}
