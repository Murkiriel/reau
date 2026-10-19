package com.reau.app.aa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measured on a device (Android Auto 17.8, head unit server turned on by hand): after Desligar the server went on
 * listening for about 70 s and accepted the next connection without answering. REau said "Android Auto isn't responding,
 * force stop it" for that minute. Within 120 s of REau's own stop it now says Android Auto is closing the previous session.
 */
class PreviousSessionClosingTest {
    private val stop = 1_000_000L

    @Test
    fun `unanswered handshakes right after the stop say the previous session is closing`() {
        // The third unanswered handshake comes some 30 to 45 s after the stop.
        for (after in listOf(0L, 1_000L, 35_000L, 70_000L, 119_999L, 120_000L)) {
            assertEquals("$after ms", AaLinkState.CLOSING_PREVIOUS, PreviousSessionClosing.unansweredState(stop + after, stop, answeredSinceStart = false))
        }
    }

    @Test
    fun `past the window the server is truly stuck and says so`() {
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(stop + 120_001L, stop, answeredSinceStart = false))
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(stop + 600_000L, stop, answeredSinceStart = false))
    }

    @Test
    fun `with no stop of REau to count from it is the old rule`() {
        // REau ended in the middle of a session and came back as a new process, or never stopped a receiver before.
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(5_000L, null, answeredSinceStart = false))
        assertFalse(PreviousSessionClosing.within(5_000L, null))
    }

    @Test
    fun `an Android Auto that answered since this receiver began and stopped answering is stuck, not closing`() {
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(stop + 20_000L, stop, answeredSinceStart = true))
    }

    // ── the notice stays when the next dial is accepted ────────────────────────────────────────────────────

    @Test
    fun `an accepted dial does not take back a closing-previous notice that still holds`() {
        for (after in listOf(0L, 37_000L, 85_000L, 120_000L)) {
            assertEquals(
                "$after ms",
                AaLinkState.CLOSING_PREVIOUS,
                PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, stop + after, stop, answeredSinceStart = false),
            )
        }
    }

    @Test
    fun `an accepted dial after the window is connecting again, as before`() {
        assertEquals(
            AaLinkState.CONNECTING,
            PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, stop + 120_001L, stop, answeredSinceStart = false),
        )
    }

    @Test
    fun `an accepted dial of a receiver that had an answer is connecting`() {
        assertEquals(
            AaLinkState.CONNECTING,
            PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, stop + 10_000L, stop, answeredSinceStart = true),
        )
    }

    @Test
    fun `an accepted dial with no stop to count from is connecting`() {
        assertEquals(
            AaLinkState.CONNECTING,
            PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, 5_000L, null, answeredSinceStart = false),
        )
    }

    @Test
    fun `any other state is connecting on an accepted dial, even inside the window`() {
        for (current in AaLinkState.entries.filter { it != AaLinkState.CLOSING_PREVIOUS && it != AaLinkState.UNRESPONSIVE }) {
            assertEquals(
                current.name,
                AaLinkState.CONNECTING,
                PreviousSessionClosing.stateOnAccept(current, stop + 10_000L, stop, answeredSinceStart = false),
            )
        }
    }

    // ── "not responding" stays too: the notice no longer flips back to "connecting" at each dial ─────────────

    @Test
    fun `an accepted dial does not take back a not-responding notice, whatever the clock or the stop say`() {
        // The stop's window over, no stop at all, a receiver that had an answer once: all stay "not responding".
        val cases = listOf(
            Triple(stop + 120_001L, stop, false),
            Triple(stop + 600_000L, stop, false),
            Triple(5_000L, null, false),
            Triple(stop + 20_000L, stop, true),
            Triple(stop + 20_000L, stop, false),
        )
        for ((now, stoppedAt, answered) in cases) {
            assertEquals(
                "$now $stoppedAt $answered",
                AaLinkState.UNRESPONSIVE,
                PreviousSessionClosing.stateOnAccept(AaLinkState.UNRESPONSIVE, now, stoppedAt, answered),
            )
        }
    }

    @Test
    fun `a stuck server's rounds say not responding from the third mute handshake on and never connecting again`() {
        // The 18/10 log: mute rounds every ~15 s (three handshake tries, then 5 s of wait), the notice flipped to "connecting"
        // at each dial and back to "not responding" at each failure. With no stop to count from it is the second state.
        var said = AaLinkState.CONNECTING
        val seen = mutableListOf<AaLinkState>()
        for (round in 1..8) {
            val now = round * 15_000L
            if (round > 1) said = PreviousSessionClosing.stateOnAccept(said, now, null, answeredSinceStart = false)
            seen += said
            if (round >= 3) said = PreviousSessionClosing.unansweredState(now + 10_000L, null, answeredSinceStart = false)
            seen += said
        }
        assertEquals(listOf(AaLinkState.CONNECTING, AaLinkState.CONNECTING), seen.take(2))
        assertEquals("not responding from the third round, no flip: $seen", setOf(AaLinkState.UNRESPONSIVE), seen.drop(5).toSet())
    }

    @Test
    fun `only an answer, the port refusing or the receiver stopping take not-responding back, not an accepted dial`() {
        // ACTIVE and SERVER_OFF are reported by the receiver itself, not by an accepted dial: after them the accept says connecting.
        for (current in listOf(AaLinkState.ACTIVE, AaLinkState.SERVER_OFF, AaLinkState.DROPPED, AaLinkState.IDLE)) {
            assertEquals(current.name, AaLinkState.CONNECTING, PreviousSessionClosing.stateOnAccept(current, stop + 10_000L, stop, false))
        }
    }

    // ── a session REau did not see end (the other edition's) ───────────────────────────────────────────────

    // The 18/10 21:47 log, in seconds from where the full edition began to dial: mute rounds ended at these instants.
    private val begun = 7_000_000L
    private val roundEnds = listOf(10.3, 21.7, 32.9, 48.2, 63.5, 78.8, 94.0, 109.3, 124.6, 139.9, 155.2).map { begun + (it * 1_000).toLong() }

    @Test
    fun `mute rounds with no stop of this REau but a doubt from where it began to dial say the previous session is closing`() {
        for (end in roundEnds.filter { it - begun <= 120_000L }) {
            assertEquals(
                "${(end - begun) / 1000} s",
                AaLinkState.CLOSING_PREVIOUS,
                PreviousSessionClosing.unansweredState(end, stoppedAtMs = null, answeredSinceStart = false, maybeSinceMs = begun),
            )
        }
    }

    @Test
    fun `the doubt ends with the window, and a server still mute after it is stuck and says so`() {
        // The log's server was mute 3 minutes: from 124.6 s on the receiver must call it stuck, not hide it.
        for (end in roundEnds.filter { it - begun > 120_000L }) {
            assertEquals(
                "${(end - begun) / 1000} s",
                AaLinkState.UNRESPONSIVE,
                PreviousSessionClosing.unansweredState(end, stoppedAtMs = null, answeredSinceStart = false, maybeSinceMs = begun),
            )
        }
    }

    @Test
    fun `without a doubt it is the old rule, and an answered receiver is never closing`() {
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(begun + 40_000L, null, answeredSinceStart = false, maybeSinceMs = null))
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(begun + 40_000L, null, answeredSinceStart = true, maybeSinceMs = begun))
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(begun - 1L, null, answeredSinceStart = false, maybeSinceMs = begun))
    }

    @Test
    fun `either window is enough to say closing`() {
        // Own stop long ago and a doubt now, or the other way round.
        assertEquals(AaLinkState.CLOSING_PREVIOUS, PreviousSessionClosing.unansweredState(begun + 50_000L, begun - 500_000L, false, maybeSinceMs = begun))
        assertEquals(AaLinkState.CLOSING_PREVIOUS, PreviousSessionClosing.unansweredState(begun + 50_000L, begun + 10_000L, false, maybeSinceMs = begun - 500_000L))
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(begun + 50_000L, begun - 500_000L, false, maybeSinceMs = begun - 400_000L))
    }

    @Test
    fun `an accepted dial keeps a closing notice held by the doubt, until the doubt ends`() {
        assertEquals(
            AaLinkState.CLOSING_PREVIOUS,
            PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, begun + 60_000L, null, false, maybeSinceMs = begun),
        )
        assertEquals(
            AaLinkState.CONNECTING,
            PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, begun + 120_001L, null, false, maybeSinceMs = begun),
        )
        assertEquals(
            AaLinkState.CONNECTING,
            PreviousSessionClosing.stateOnAccept(AaLinkState.CLOSING_PREVIOUS, begun + 60_000L, null, true, maybeSinceMs = begun),
        )
    }

    @Test
    fun `the doubt begins once per mute stretch, a receiver started again inside it does not renew it`() {
        AaRestartGap.maybeClosingSinceMs = null
        try {
            AaRestartGap.dialBegun(1_000L)
            AaRestartGap.dialBegun(90_000L)
            assertEquals("the first receiver's start stays", 1_000L, AaRestartGap.maybeClosingSinceMs)
            assertEquals(
                "so a second receiver at 130 s, the server still mute, is stuck",
                AaLinkState.UNRESPONSIVE,
                PreviousSessionClosing.unansweredState(130_000L, null, false, AaRestartGap.maybeClosingSinceMs),
            )
            AaRestartGap.portRefused()
            assertNull("a refused port ends it: the server that was closing is gone", AaRestartGap.maybeClosingSinceMs)
            AaRestartGap.dialBegun(200_000L)
            assertEquals("and the next receiver begins a new one", 200_000L, AaRestartGap.maybeClosingSinceMs)
        } finally {
            AaRestartGap.maybeClosingSinceMs = null
        }
    }

    @Test
    fun `the doubt is a window as long as the other, so it cannot hide a stuck server for longer`() {
        assertTrue(PreviousSessionClosing.maybeClosing(begun + PreviousSessionClosing.WINDOW_MS, begun))
        assertFalse(PreviousSessionClosing.maybeClosing(begun + PreviousSessionClosing.WINDOW_MS + 1, begun))
        assertFalse(PreviousSessionClosing.maybeClosing(begun, null))
    }

    @Test
    fun `a whole closing window of rounds never flips back to connecting`() {
        // The real session: mute rounds accepted at +6, +43, +72 and +79 s of the window, closing said from +37 s.
        var said = AaLinkState.IDLE
        val seen = mutableListOf<AaLinkState>()
        for ((at, closing) in listOf(6L to false, 37L to true, 43L to false, 67L to true, 72L to false, 79L to true)) {
            val now = stop + at * 1_000
            val next = if (closing) PreviousSessionClosing.unansweredState(now, stop, false)
            else PreviousSessionClosing.stateOnAccept(said, now, stop, false)
            said = next
            seen += next
        }
        assertEquals(listOf(AaLinkState.CONNECTING), seen.take(1))
        assertEquals("closing from the first unanswered round on: $seen", listOf(AaLinkState.CLOSING_PREVIOUS), seen.drop(1).distinct())
    }

    @Test
    fun `a clock that went back is no window`() {
        assertFalse(PreviousSessionClosing.within(stop - 1L, stop))
    }

    // ── the stop kept by a process that died ───────────────────────────────────────────────────────────────

    // Wall clock and elapsed clock at the stop: the two numbers the file keeps.
    private val savedWall = 1_760_000_000_000L
    private val savedElapsed = 50_000_000L

    @Test
    fun `a process that comes back inside the window gets the stop back, as an elapsed instant`() {
        val restored = PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 40_000L, savedElapsed + 40_000L)
        assertEquals(savedElapsed, restored)
        val nowElapsed = savedElapsed + 40_000L
        assertEquals(AaLinkState.CLOSING_PREVIOUS, PreviousSessionClosing.unansweredState(nowElapsed, restored, answeredSinceStart = false))
    }

    @Test
    fun `a stop restored from long ago is past the window and says stuck`() {
        val restored = PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 300_000L, savedElapsed + 300_000L)
        assertEquals(savedElapsed, restored)
        assertEquals(AaLinkState.UNRESPONSIVE, PreviousSessionClosing.unansweredState(savedElapsed + 300_000L, restored, answeredSinceStart = false))
    }

    @Test
    fun `a phone that restarted is no window, even with the wall clock inside 120 s`() {
        // After a boot the elapsed clock starts again: it is behind the saved one...
        assertNull(PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 60_000L, 30_000L))
        // ...or, if the phone was off long enough, ahead of it by much less than the wall clock went on.
        assertNull(PreviousSessionClosing.restoredStop(savedWall, 1_000L, savedWall + 60_000L, 40_000L))
    }

    @Test
    fun `a wall clock set back is no window`() {
        assertNull(PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall - 10_000L, savedElapsed + 10_000L))
        assertNull(PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 10_000L, savedElapsed - 1L))
    }

    @Test
    fun `a clock set forward or back by more than the skew is no window, by less it still is`() {
        val within = PreviousSessionClosing.CLOCK_SKEW_MS
        assertEquals(savedElapsed, PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 30_000L + within, savedElapsed + 30_000L))
        assertEquals(savedElapsed, PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 30_000L - within, savedElapsed + 30_000L))
        assertNull(PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 30_000L + within + 1, savedElapsed + 30_000L))
        assertNull(PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall + 30_000L - within - 1, savedElapsed + 30_000L))
    }

    @Test
    fun `the instant of the stop itself restores, and the skew is smaller than the window`() {
        assertEquals(savedElapsed, PreviousSessionClosing.restoredStop(savedWall, savedElapsed, savedWall, savedElapsed))
        assertTrue(PreviousSessionClosing.CLOCK_SKEW_MS < PreviousSessionClosing.WINDOW_MS / 10)
    }

    @Test
    fun `the window covers the 70 s measured with margin, and the redial is not slowed`() {
        assertTrue(PreviousSessionClosing.WINDOW_MS >= 70_000L + 30_000L)
        assertEquals("the first dial after the stop is not delayed by this rule", 0L, AaRestartGap.delayMs(stop + AaRestartGap.GAP_MS, stop))
    }

    @Test
    fun `a streak that was never answered is not marked answered, one that was is`() {
        val s = HandshakeStreak()
        assertFalse(s.everAnswered)
        s.after(handshook = false)
        s.after(handshook = false)
        assertTrue(s.after(handshook = false))
        assertFalse(s.everAnswered)
        s.after(handshook = true)
        assertTrue(s.everAnswered)
        s.after(handshook = false)
        assertTrue("it stays marked", s.everAnswered)
    }

    @Test
    fun `the closing texts tell the truth in both languages and never suggest a force stop`() {
        val src = listOf(File("src"), File("app/src")).first { File(it, "main/res").isDirectory }
        val keys = listOf("headline_android_auto_closing_previous", "sub_closing_previous", "aa_closing_previous_warning", "notif_text_closing_previous", "warn_closing_previous_title", "link_closing_previous", "notif_title_closing_previous")
        for ((folder, force) in listOf("values" to Regex("force", RegexOption.IGNORE_CASE), "values-pt" to Regex("for[çc]", RegexOption.IGNORE_CASE))) {
            val text = File(src, "main/res/$folder/strings.xml").readText()
            for (key in keys) {
                val found = Regex("""<string name="$key">(.*?)</string>""").find(text)
                assertTrue("$key ($folder) exists", found != null)
                assertFalse("$key ($folder) suggests a force stop", force.containsMatchIn(found!!.groupValues[1]))
            }
        }
        val en = File(src, "main/res/values/strings.xml").readText()
        val pt = File(src, "main/res/values-pt/strings.xml").readText()
        assertTrue(Regex("""name="aa_closing_previous_warning">[^<]*1 minute[^<]*head unit server""").containsMatchIn(en))
        assertTrue(Regex("""name="aa_closing_previous_warning">[^<]*1 minuto[^<]*servidor da unidade principal""").containsMatchIn(pt))
    }

    // -- the window running out ---------------------------------------------------------------------------

    @Test
    fun `the window runs out in the round where closing gives way to not responding, and only there`() {
        assertTrue(PreviousSessionClosing.windowEnded(AaLinkState.CLOSING_PREVIOUS, AaLinkState.UNRESPONSIVE))
        assertFalse("still closing", PreviousSessionClosing.windowEnded(AaLinkState.CLOSING_PREVIOUS, AaLinkState.CLOSING_PREVIOUS))
        assertFalse("already stuck", PreviousSessionClosing.windowEnded(AaLinkState.UNRESPONSIVE, AaLinkState.UNRESPONSIVE))
        assertFalse("first round", PreviousSessionClosing.windowEnded(null, AaLinkState.UNRESPONSIVE))
        assertFalse("first round, closing", PreviousSessionClosing.windowEnded(null, AaLinkState.CLOSING_PREVIOUS))
    }

    /**
     * The mute run of 18/10 (full edition, Android Auto 17.8): dialling from t0, a round of three unanswered handshakes
     * every ~15 s (the first at 33 s), the server still mute at 185 s. The doubt counts from t0 with the window of
     * [PreviousSessionClosing.WINDOW_MS]: the rounds report closing up to 109 s, and not responding from the round at ~124 s,
     * which is where the one restart is due: 60 s before the 185 s the rider waited.
     */
    @Test
    fun `in the mute run of 18-10 the restart comes at the first round after 120 s, not later`() {
        val t0 = 5_000_000L
        val rounds = listOf(33, 48, 63, 78, 94, 109, 124, 140, 155, 170).map { t0 + it * 1_000L }
        val says = rounds.map { PreviousSessionClosing.unansweredState(it, null, answeredSinceStart = false, maybeSinceMs = t0) }
        assertEquals(List(6) { AaLinkState.CLOSING_PREVIOUS } + List(4) { AaLinkState.UNRESPONSIVE }, says)
        var previous: AaLinkState? = null
        val ended = says.indices.filter { i -> PreviousSessionClosing.windowEnded(previous, says[i]).also { previous = says[i] } }
        assertEquals("the window runs out once, at the round of 124 s", listOf(6), ended)
        // And the policy turns that round into the restart, once.
        val policy = ServerPolicy().apply { started() }
        val acts = says.map { policy.state(it) }
        assertEquals(listOf(6), acts.indices.filter { acts[it] == ServerPolicy.Act.RESTART })
    }
}
