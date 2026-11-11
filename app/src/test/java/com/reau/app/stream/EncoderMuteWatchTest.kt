package com.reau.app.stream

import com.reau.app.dash.video.PendingFrames
import com.reau.app.settings.REauSettings
import com.reau.app.stream.EncoderMuteWatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The experimental watch for an encoder that takes frames in and gives none out, with no error. Off (the default) there is no watch at all:
 * the loop rebuilds only an encoder that fails, as before.
 */
class EncoderMuteWatchTest {
    private val watch = EncoderMuteWatch()

    /** Polls every 250 ms from [fromMs] to [toMs] inclusive, each with [submitted] frames in and none out: the verdicts that were not FINE, with their times. */
    private fun mute(fromMs: Long, toMs: Long, submitted: Int = 1): List<Pair<Long, Verdict>> =
        (fromMs..toMs step 250).map { it to watch.onPoll(it, submitted, 0) }.filter { it.second != Verdict.FINE }

    @Test
    fun `the switch is off by default, and off there is no watch`() {
        assertFalse(REauSettings().experimentEncoderWatch)
        assertFalse(VideoLoop.Settings().encoderMuteWatch)
        assertNull(EncoderMuteWatch.whenOn(false))
        assertNotNull(EncoderMuteWatch.whenOn(true))
    }

    @Test
    fun `an encoder that gives its frames out is never rebuilt`() {
        for (t in 0L..60_000L step 250) assertEquals(Verdict.FINE, watch.onPoll(t, submitted = 1, framesOut = 1))
        assertEquals(0, watch.rebuilds)
    }

    @Test
    fun `nothing going in is not mute, however long`() {
        for (t in 0L..60_000L step 250) assertEquals(Verdict.FINE, watch.onPoll(t, submitted = 0, framesOut = 0))
    }

    @Test
    fun `frames in and none out for 3 s is a rebuild, at 3 s and not before`() {
        assertEquals(listOf(4_000L to Verdict.REBUILD), mute(1_000, 4_000))
        assertEquals(1, watch.rebuilds)
        assertEquals("the frames that went in during the wait", 13, watch.lastWaitFrames)
    }

    @Test
    fun `one poll short of 3 s is not yet a rebuild`() {
        assertEquals(emptyList<Pair<Long, Verdict>>(), mute(1_000, 3_750))
    }

    @Test
    fun `two frames in 3 s are not enough, the third is`() {
        assertEquals(Verdict.FINE, watch.onPoll(0, 1, 0))
        assertEquals(Verdict.FINE, watch.onPoll(1_500, 1, 0))
        assertEquals("3 s, but only two frames went in", Verdict.FINE, watch.onPoll(3_000, 0, 0))
        assertEquals(Verdict.REBUILD, watch.onPoll(3_250, 1, 0))
    }

    @Test
    fun `a frame that comes out starts the wait over`() {
        mute(0, 2_750)
        assertEquals(Verdict.FINE, watch.onPoll(2_900, 1, 1))
        assertEquals("3 s count from the next frame held", listOf(6_000L to Verdict.REBUILD), mute(3_000, 6_000))
    }

    @Test
    fun `the wait goes on through polls with nothing in, once a frame is held`() {
        assertEquals(Verdict.FINE, watch.onPoll(0, 3, 0))
        for (t in 250L..2_750L step 250) assertEquals(Verdict.FINE, watch.onPoll(t, 0, 0))
        assertEquals(Verdict.REBUILD, watch.onPoll(3_000, 0, 0))
    }

    @Test
    fun `the time with nothing going in before the first held frame does not count`() {
        for (t in 0L..10_000L step 250) assertEquals(Verdict.FINE, watch.onPoll(t, 0, 0))
        assertEquals("three frames go in at once after 10 s of nothing: the wait starts here", Verdict.FINE, watch.onPoll(10_250, 3, 0))
        assertEquals(Verdict.FINE, watch.onPoll(13_000, 0, 0))
        assertEquals(Verdict.REBUILD, watch.onPoll(13_250, 0, 0))
    }

    @Test
    fun `a stream gets three rebuilds, then one word that it gives up, then silence`() {
        val verdicts = mute(0, 30_000)
        assertEquals(
            listOf(3_000L to Verdict.REBUILD, 6_250L to Verdict.REBUILD, 9_500L to Verdict.REBUILD, 12_750L to Verdict.GIVE_UP),
            verdicts,
        )
        assertEquals(3, watch.rebuilds)
    }

    @Test
    fun `the lines of the ride log say what happened and how many times`() {
        val rebuild = EncoderMuteWatch.line(Verdict.REBUILD, framesIn = 12, rebuilds = 2)
        assertEquals("encoder mute (experiment): 12 frames went in and none came out for 3000 ms, with no error: rebuilding it (2 of 3)", rebuild)
        assertTrue(EncoderMuteWatch.line(Verdict.GIVE_UP, 12, 3).endsWith("after 3 rebuilds: left as it is"))
        assertEquals("", EncoderMuteWatch.line(Verdict.FINE, 0, 0))
    }

    @Test
    fun `the encoder's statistics say how many frames went in since the last look, apart from the ones that came out`() {
        val pending = PendingFrames()
        pending.submitted(10, 1_000)
        pending.submitted(20, 2_000)
        pending.submitted(30, 3_000)
        pending.emitted(4_000)
        val first = pending.takeStats()
        assertEquals(3, first.submitted)
        assertEquals(1, first.frames)
        val second = pending.takeStats()
        assertEquals("counted once", 0, second.submitted)
        assertEquals("the counts compared before are as they were", PendingFrames.Stats(0, 0, 0L), second)
    }
}
