package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The tolerant watch of the dash's silence and the control packets 12 ms apart, both on one experimental switch. */
class RxQuietTest {
    private val quiet = RxQuiet()
    private val last = 100_000L

    @Test
    fun `the numbers are 12 seconds noted, 60 seconds to give up, and the 4 seconds of today stay the default`() {
        assertEquals(12_000L, RxQuiet.NOTE_MS)
        assertEquals(60_000L, RxQuiet.GIVE_UP_MS)
        assertEquals(12L, ControlGap.GAP_MS)
        assertEquals(4_000L, DashSession.Timing().rxWatchdogMs)
        assertEquals(60_000L, DashSession.Timing().rxGiveUpMs)
        assertEquals(12_000L, DashSession.Timing().rxNoteMs)
    }

    @Test
    fun `a silence up to 12 seconds says nothing, one over it is noted once`() {
        assertNull(quiet.look(last + 12_000, last, streaming = true, heartbeatsSinceRx = 12))
        val noted = quiet.look(last + 12_001, last, true, 12)!!
        assertEquals(RxQuiet.Kind.QUIET, noted.kind)
        assertEquals(12_001L, noted.ms)
        assertNull("once", quiet.look(last + 13_001, last, true, 13))
        assertNull(quiet.look(last + 50_000, last, true, 50))
    }

    @Test
    fun `when the dash speaks again after a noted silence the look says so with how long it was`() {
        quiet.look(last + 15_000, last, true, 15)
        val back = quiet.look(last + 20_000, last + 18_000, true, 1)!!
        assertEquals(RxQuiet.Kind.BACK, back.kind)
        assertEquals(18_000L, back.ms)
        assertNull("only once", quiet.look(last + 21_000, last + 18_000, true, 2))
    }

    @Test
    fun `a silence is noted again after the dash came back and fell silent anew`() {
        quiet.look(last + 15_000, last, true, 15)
        quiet.look(last + 20_000, last + 18_000, true, 1)
        val again = quiet.look(last + 18_000 + 12_001, last + 18_000, true, 12)!!
        assertEquals(RxQuiet.Kind.QUIET, again.kind)
    }

    @Test
    fun `a dash that comes back before 12 seconds is no event`() {
        assertNull(quiet.look(last + 8_000, last, true, 8))
        assertNull(quiet.look(last + 9_000, last + 8_500, true, 1))
    }

    @Test
    fun `nothing is noted when the session does not stream, the dash never spoke, or the phone did not send for long enough`() {
        assertNull(quiet.look(last + 30_000, last, streaming = false, heartbeatsSinceRx = 30))
        assertNull(quiet.look(last + 30_000, 0L, streaming = true, heartbeatsSinceRx = 30))
        assertNull("three heartbeats: the phone may have been paused", quiet.look(last + 30_000, last, true, 3))
        assertEquals(RxQuiet.Kind.QUIET, quiet.look(last + 30_000, last, true, 4)!!.kind)
    }

    @Test
    fun `leaving streaming or switching off forgets a noted silence`() {
        quiet.look(last + 15_000, last, true, 15)
        quiet.look(last + 16_000, last, streaming = false, heartbeatsSinceRx = 16)
        assertEquals(RxQuiet.Kind.QUIET, quiet.look(last + 17_000, last, true, 17)!!.kind)
        quiet.reset()
        assertEquals("reset: noted again", RxQuiet.Kind.QUIET, quiet.look(last + 18_000, last, true, 18)!!.kind)
    }

    @Test
    fun `the log lines say the seconds and the way the watch ends`() {
        assertEquals("RX quiet: no dash packets for 12.0 s; the session goes on and ends at 60 s of silence (tolerant watch, experiment)", RxQuiet.quietLine(12_000))
        assertEquals("RX back: the dash spoke again after 18.5 s of silence; a keyframe is asked (tolerant watch, experiment)", RxQuiet.backLine(18_500))
    }

    @Test
    fun `the session ends at 4 seconds today and at 60 with the switch, never in between with it on`() {
        val streaming = DashState.STREAMING
        val timing = DashSession.Timing()
        // off: the limit is the 4 s of today
        assertEquals(true, DashSession.rxSilenceFails(streaming, last, last + 4_001, timing.rxWatchdogMs, 5))
        // on: the same silence is not the end, 59 s is not, 61 s is
        assertEquals(false, DashSession.rxSilenceFails(streaming, last, last + 59_000, timing.rxGiveUpMs, 60))
        assertEquals(true, DashSession.rxSilenceFails(streaming, last, last + 60_001, timing.rxGiveUpMs, 61))
    }

    @Test
    fun `the heartbeat loop picks the limit by the switch and asks the video for a keyframe when the dash is back`() {
        val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        assertTrue(session.contains("val silenceLimit = if (tolerant) timing.rxGiveUpMs else timing.rxWatchdogMs"))
        assertTrue(session.contains("rxSilenceFails(_state.value, lastRxMs, MonotonicClock.nowMs(), silenceLimit, heartbeatsSinceRx)"))
        assertTrue(session.contains("if (!tolerant) return quiet.reset()"))
        assertTrue(session.contains("onRxBack?.invoke(verdict.ms)"))
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("tolerantRx = { tuned?.experimentTolerantRx == true },"))
        assertTrue(live.contains("session.onRxBack = { silentMs -> loop?.restartStream("))
        assertTrue(live.contains("controlGapMs = if (now.experimentTolerantRx) ControlGap.GAP_MS else 0L"))
    }

    // ── the control packets 12 ms apart ──

    @Test
    fun `with no gap set nothing ever waits`() {
        val gap = ControlGap()
        for (i in 0 until 5) assertEquals(0L, gap.waitNs(1_000L))
        assertEquals(0L, ControlGap(0L).waitNs(5L))
    }

    @Test
    fun `with 12 ms the first packet goes at once and each next one waits for its slot`() {
        val gap = ControlGap(ControlGap.GAP_MS)
        assertEquals(0L, gap.waitNs(1_000_000_000L))
        assertEquals(12_000_000L, gap.waitNs(1_000_000_000L))
        assertEquals("a third at the same instant waits for the second's slot and one more", 24_000_000L, gap.waitNs(1_000_000_000L))
    }

    @Test
    fun `a packet that comes late waits for nothing, and the gap counts from when the last one really left`() {
        val gap = ControlGap(ControlGap.GAP_MS)
        gap.waitNs(0L)
        assertEquals("30 ms later: free", 0L, gap.waitNs(30_000_000L))
        assertEquals("5 ms after that: waits the other 7", 7_000_000L, gap.waitNs(35_000_000L))
    }

    @Test
    fun `the socket holds a packet only when it was made with a gap`() {
        val socket = File("src/main/java/com/reau/app/dash/DashSocket.kt").readText()
        assertTrue(socket.contains("private val controlGap = ControlGap(controlGapMs)"))
        assertTrue(socket.contains("waitForTheGap()\n        val pkt = K1GPacket.patchSeq(data, seq.getAndIncrement())"))
        assertTrue(socket.contains("controlGapMs: Long = 0L,"))
    }
}
