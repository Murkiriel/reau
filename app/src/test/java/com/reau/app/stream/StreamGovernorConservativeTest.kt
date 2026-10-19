package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against the dash lagging at speed: the rider's bitrate counts with the automatic frame rate
 * too, the keyframe can be fixed, and every 5 s line says how fast the bike went and how the bike's Wi-Fi was.
 */
class StreamGovernorConservativeTest {
    private var nowMs = 0L

    private fun governor(fps: Int = 4, bitrateBps: Int = 200_000, keyframeSec: Int = 1) =
        StreamGovernor(AutoFpsController.Plan(fps, fps, null), bitrateCeilingBps = bitrateBps, fixedKeyframeSec = keyframeSec)

    private fun StreamGovernor.poll(drawn: Int = 0, bytesOut: Long = drawn * 1_000L, link: LinkSample? = null): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn * 2, drawn = drawn, repeated = 0, composeNs = drawn * 2_000_000L),
                framesOut = drawn, bytesOut = bytesOut, lateFrames = 0, encodeNs = drawn * 8_000_000L,
                dashAcks = 0, firstFrameConfirmed = true, link = link,
            ),
        )
    }

    /** One 5 s window at [fps] with [bytes] sent, every poll carrying [link]. */
    private fun StreamGovernor.window(fps: Int, bytes: Long, link: (Int) -> LinkSample? = { null }): List<Action> =
        (0 until POLLS_PER_WINDOW).flatMap { i ->
            fun share(total: Long) = total * (i + 1) / POLLS_PER_WINDOW - total * i / POLLS_PER_WINDOW
            val frames = fps * 5
            poll(drawn = share(frames.toLong()).toInt(), bytesOut = share(bytes), link = link(i))
        }

    private fun started(fps: Int = 4, bitrateBps: Int = 200_000, keyframeSec: Int = 1) = governor(fps, bitrateBps, keyframeSec).also {
        it.start(nowMs)
        it.poll(drawn = 1)
    }

    @Test
    fun `at 4 frames a second and 200 kbps the stream runs at 200 moving and 100 still`() {
        val g = governor()
        assertTrue(Action.SetBitrate(100_000) in g.start(nowMs))
        assertEquals(200_000, g.activeBitrateBps)
        assertTrue(Action.SetBitrate(200_000) in (1..3).flatMap { g.poll(drawn = 1) })
    }

    @Test
    fun `a faster rate still gets its bits per frame, never past the ceiling`() {
        assertEquals("12 fps need 410 kbps", 500_000, governor(fps = 12).activeBitrateBps)
        assertEquals(1_024_000, governor(fps = 30).activeBitrateBps)
        assertEquals("the rider's higher choice stays", 800_000, governor(fps = 4, bitrateBps = 800_000).activeBitrateBps)
    }

    @Test
    fun `a fixed keyframe interval never changes, even with the bitrate used up`() {
        val g = started(keyframeSec = 1)
        // 192 kbps out of 200: what the automatic keyframe reads as a tight budget, and moves to 2 s.
        val actions = (1..14).flatMap { g.window(fps = 4, bytes = 120_000) }
        assertTrue("got ${actions.filterIsInstance<Action.Rebuild>()}", actions.none { it is Action.Rebuild })
        assertEquals(1, g.keyframeSec)
        assertTrue(actions.filterIsInstance<Action.Log>().last().line.contains(" kf=1s "))
    }

    @Test
    fun `the automatic keyframe still decides by itself`() {
        val g = started(keyframeSec = 0)
        val actions = (1..14).flatMap { g.window(fps = 4, bytes = 120_000) }
        assertTrue(Action.Rebuild(4, 2, 200_000) in actions)
        assertEquals(2, g.keyframeSec)
    }

    @Test
    fun `every 5 s line says the speed and the bike's Wi-Fi`() {
        val g = started()
        val lines = g.window(fps = 4, bytes = 100_000) { i ->
            if (i < 10) LinkSample(speedKmh = 80.0, wifiDbm = -60, wifiMbps = 52) else LinkSample(speedKmh = 100.0, wifiDbm = -71, wifiMbps = 26)
        }.filterIsInstance<Action.Log>().map { it.line }
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains(" speed~90km/h(max 100) wifi=-71dBm/26Mbps(worst)"))
        val none = g.window(fps = 4, bytes = 100_000).filterIsInstance<Action.Log>().single().line
        assertTrue(none, none.contains(" speed=? wifi=? "))
    }

    private companion object {
        const val POLL_MS = 250L
        const val POLLS_PER_WINDOW = 20
    }
}
