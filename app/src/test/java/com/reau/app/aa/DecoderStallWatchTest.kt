package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the video decoder counts as stuck. Android Auto sends pictures only while its screen changes and stops
 * altogether for seconds at a time (a still map at a traffic light, its app list, a call): silence from the phone
 * is not a stuck decoder, and neither is the first picture after the silence.
 */
class DecoderStallWatchTest {
    private val watch = DecoderStallWatch()

    /** Input every [everyMs] from [fromMs] up to and including [toMs]; returns whether a stall was ever seen. */
    private fun feed(fromMs: Long, toMs: Long, everyMs: Long = 33, answered: Boolean = false): Boolean {
        var stalled = false
        var t = fromMs
        while (t <= toMs) {
            watch.onInput(t)
            if (watch.isStalled(t + 5)) stalled = true
            if (answered) watch.onOutput()
            t += everyMs
        }
        return stalled
    }

    @Test
    fun `pictures going in and coming out is never a stall`() {
        assertFalse(feed(1_000, 20_000, answered = true))
    }

    @Test
    fun `nothing at all has happened yet`() {
        assertFalse(watch.isStalled(50_000))
    }

    @Test
    fun `input that flows for over three seconds with nothing coming out is a stall`() {
        feed(1_000, 4_000)
        assertFalse("three seconds exactly is still patience", watch.isStalled(4_000))
        watch.onInput(4_001)
        assertTrue(watch.isStalled(4_001))
    }

    @Test
    fun `a stall is measured from the first input left unanswered, not from the last picture`() {
        feed(1_000, 2_000, answered = true)
        // Nothing for 400 ms (not long enough to be a pause), then input with no output.
        assertFalse(feed(2_400, 5_400))
        watch.onInput(5_401)
        assertTrue(watch.isStalled(5_401))
    }

    @Test
    fun `silence from the phone is a pause, however long`() {
        feed(1_000, 2_000, answered = true)
        for (t in 2_000L..60_000L step 10) assertFalse("at $t", watch.isStalled(t))
    }

    @Test
    fun `the first input after a pause is not a stall`() {
        feed(1_000, 2_000, answered = true)
        watch.onInput(6_500) // the phone resumes 4.5 s later; the decoder has not answered yet
        assertFalse("the decoder has had no time to answer", watch.isStalled(6_501))
        assertFalse(watch.isStalled(6_510))
    }

    @Test
    fun `a pause with an input still unanswered before it is not a stall on resume either`() {
        feed(1_000, 2_000, answered = true)
        watch.onInput(2_033) // e.g. a codec configuration: the decoder gives no picture for it
        watch.onInput(8_000) // resume after almost 6 s
        assertFalse(watch.isStalled(8_005))
    }

    @Test
    fun `after a pause the three seconds start again, and a decoder that stays silent is caught`() {
        feed(1_000, 2_000, answered = true)
        assertFalse(feed(6_500, 9_500))
        watch.onInput(9_501)
        assertTrue(watch.isStalled(9_501))
    }

    @Test
    fun `a pause is a second without input, to the millisecond, on both sides of the rule`() {
        watch.onInput(1_000)
        watch.onInput(1_999) // 999 ms later: the same run of input
        assertEquals(1_100, watch.waitingMs(2_100))
        watch.onInput(2_999) // exactly a second later: a pause came before it, the wait starts here
        assertEquals(100, watch.waitingMs(3_099))
    }

    @Test
    fun `input that stopped a second ago is the phone pausing, not a stall`() {
        feed(1_000, 4_400)
        watch.onInput(4_500) // 3.5 s of input, no output, and then nothing more
        assertTrue(watch.isStalled(4_500 + 999))
        assertFalse(watch.isStalled(4_500 + 1_000))
    }

    @Test
    fun `an output clears the wait and the next input starts a new one`() {
        feed(1_000, 3_900) // 2.9 s unanswered
        watch.onOutput()
        assertFalse(feed(3_933, 6_900))
        watch.onInput(6_934)
        assertTrue("3.001 s after the first input that followed the output", watch.isStalled(6_934))
    }

    @Test
    fun `a decoder that answers one picture late is not stalled`() {
        var stalled = false
        var t = 1_000L
        watch.onInput(t)
        while (t < 20_000) {
            t += 33
            watch.onInput(t) // the next picture goes in
            watch.onOutput() // and only then the previous one comes out
            if (watch.isStalled(t + 5)) stalled = true
        }
        assertFalse(stalled)
    }

    @Test
    fun `the gaps reported for the log are the wait and the time since the last input`() {
        watch.onInput(1_000)
        watch.onInput(1_500)
        assertEquals(1_200, watch.waitingMs(2_200))
        assertEquals(700, watch.inputGapMs(2_200))
        watch.onOutput()
        assertEquals("nothing is waiting", 0, watch.waitingMs(2_300))
    }

    /**
     * Opening a codec can take seconds on some phones, and the thread that feeds it is the one that opens it: the
     * input that made it open is followed by nothing until it is open. To the watch that is a pause.
     */
    @Test
    fun `a decoder that took long to open is not stalled by the input that made it open`() {
        watch.onInput(1_000) // opening takes until 4.4 s
        watch.onInput(4_400)
        assertFalse("3.4 s after the first input, but the decoder has just opened", watch.isStalled(4_401))
        assertEquals(1, watch.waitingMs(4_401))

        assertFalse(feed(4_433, 7_400))
        watch.onInput(7_401)
        assertTrue("three seconds of input since it opened", watch.isStalled(7_401))
    }

    /** Without this a restarted decoder inherits the wait that got the one before it restarted, and is restarted
     *  at its first input, for ever. */
    @Test
    fun `a decoder opened in place of a stalled one starts with nothing owed`() {
        feed(1_000, 4_000)
        watch.onInput(4_001)
        assertTrue(watch.isStalled(4_001))

        watch.reset() // the new decoder is open; input never stopped
        watch.onInput(4_034)
        assertFalse(watch.isStalled(4_035))
        assertFalse(feed(4_067, 7_000))
        watch.onInput(7_035)
        assertTrue("it gets its own three seconds, and no more", watch.isStalled(7_035))
    }

    @Test
    fun `the thresholds can be set`() {
        val quick = DecoderStallWatch(stallMs = 100, pauseMs = 50)
        quick.onInput(1_000)
        quick.onInput(1_040)
        quick.onInput(1_080)
        quick.onInput(1_101)
        assertTrue(quick.isStalled(1_101))
        assertFalse("50 ms without input is a pause here", quick.isStalled(1_151))
        quick.onInput(1_200) // after a pause of 99 ms the wait starts again
        assertFalse(quick.isStalled(1_201))
    }
}
