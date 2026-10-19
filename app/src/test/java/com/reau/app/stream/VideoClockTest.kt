package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock the video carries (the RTP time of each frame), and the test knob that makes it run a little slower than
 * real time.
 *
 * What was seen on the dash: with the frame loop that no longer waits for the encoder, at a fixed
 * 16 fps, the picture was late by a constant 2–3 s — no freeze, no blocks, no drop; and at 30 fps the dash played
 * "fast-forward" before dropping. Both are a player holding a few seconds of video: fast-forward is it spending a
 * backlog, the constant lag is the part it never spends because frames arrive exactly on their own schedule.
 *
 * Before, they did not: the loop waited for the encoder and delivered about 15.4 fps when asked for 16, so a
 * frame stamped 62 ms after the previous one really left 65 ms after it — the video's clock ran about 5% slow, every
 * frame reached the dash "late" by its own stamp, and the dash showed it at once. That was never designed. This knob
 * does the same thing on purpose, to be tried on the bike.
 */
class VideoClockTest {

    @Test
    fun `with the knob at zero the clock advances by exactly the frame interval, as always`() {
        var pts = 0.0
        repeat(1000) { pts = DashLogic.nextVideoPts(pts, frameMs = 62, slowPercent = 0) }
        assertEquals(62_000.0, pts, 0.0)
        assertEquals(250.0, DashLogic.nextVideoPts(0.0, frameMs = 250, slowPercent = 0), 0.0)
    }

    @Test
    fun `five percent slow is the clock of the loop that waited for the encoder`() {
        // Measured in use: asked for 16 fps (62 ms a frame), delivered 15.4 (65 ms a frame): 62/65 = 95.4%.
        var pts = 0.0
        repeat(1000) { pts = DashLogic.nextVideoPts(pts, frameMs = 62, slowPercent = 5) }
        assertEquals(58_900.0, pts, 1e-6)
    }

    @Test
    fun `the slower clock still only goes forward, one frame after another`() {
        for (percent in listOf(0, 3, 5, 8, 10)) {
            var pts = 0.0
            var last = -1L
            repeat(500) {
                pts = DashLogic.nextVideoPts(pts, frameMs = 33, slowPercent = percent)
                assertTrue("at $percent%: two frames with one stamp", pts.toLong() > last)
                last = pts.toLong()
            }
        }
    }

    @Test
    fun `the fraction of a millisecond is carried, not lost, frame after frame`() {
        // 33 ms at 3% slow is 32.01 ms a frame: rounding each step down to 32 would make it 3.03% slow... and at
        // 250 ms a frame the error of whole-millisecond steps would be larger. 1000 frames: exactly 32 010 ms.
        var pts = 0.0
        repeat(1000) { pts = DashLogic.nextVideoPts(pts, frameMs = 33, slowPercent = 3) }
        assertEquals(32_010.0, pts, 1e-6)
    }

    @Test
    fun `the clock knob offers on time and three slower settings, 5 percent among them`() {
        // The knob is an experiment, so it starts at "on time", and 5% — what the old loop did — is
        // one of its choices.
        assertEquals(listOf(0, 3, 5, 8), com.reau.app.dash.DashConfig.STREAM_CLOCK_SLOW_OPTIONS)
    }

    @Test
    fun `a value outside the knob's range is brought inside it`() {
        assertEquals(62.0, DashLogic.nextVideoPts(0.0, frameMs = 62, slowPercent = -5), 0.0)
        assertEquals(55.8, DashLogic.nextVideoPts(0.0, frameMs = 62, slowPercent = 40), 1e-9)
    }
}
