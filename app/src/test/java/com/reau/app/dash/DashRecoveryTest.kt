package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride log of 31/10/2026 (10:04 to 10:11, `ride-20261031-100437.log`): the dash's Wi-Fi went away for 93 s and came back on another channel.
 * At 10:06:56.148 the loss watch put out again the request #8 it had out for 4.8 s, 47 ms before reusing the "already connected" Wi-Fi of that
 * very request; Android took the network down, REau kept it as connected, and the session's sockets were refused with EPERM on the same
 * network 454, every 30 s, for 4.5 minutes, until the rider switched off.
 */
class DashRecoveryTest {
    private val sec = 1_000_000L

    /** The numbers of the log: lost at 0, the dash seen 93 s later, request #8 out for 4.8 s. */
    private fun atTheSighting(hold: HoldWhileJoining, nowMs: Long, joining: Boolean, calls: IntArray = IntArray(1)) = DashJoin.refileAfterLossHeld(
        firstApproval = false, lostAtUs = 0L, sightingStampUs = 93 * sec, nowUs = 96 * sec, requestAgeMs = 4_814L, inPause = false,
        sinceLastRefileMs = null, hold = hold, nowMs = nowMs, joiningNow = { calls[0]++; joining },
    )

    // ── The request that is being answered is not let go ───────────────────────────────────────────

    @Test
    fun `the request of the log, with the dash's Wi-Fi already joined, is held and not put out again`() {
        val hold = HoldWhileJoining()
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheSighting(hold, nowMs = 1_000L, joining = true))
        assertEquals("the next look, 2 s on, still holds", DashJoin.LossRefile.HELD, atTheSighting(hold, nowMs = 3_000L, joining = true))
        assertEquals(DashJoin.LossRefile.HELD, atTheSighting(hold, nowMs = 1_000L + DashJoin.HOLD_WHILE_JOINING_MS - 1, joining = true))
    }

    @Test
    fun `a joined Wi-Fi that is never answered is not held for ever, the old way applies after the hold`() {
        val hold = HoldWhileJoining()
        atTheSighting(hold, nowMs = 1_000L, joining = true)
        assertEquals(DashJoin.LossRefile.REFILE, atTheSighting(hold, nowMs = 1_000L + DashJoin.HOLD_WHILE_JOINING_MS, joining = true))
    }

    @Test
    fun `with nothing joined the request is put out again as before`() {
        assertEquals(DashJoin.LossRefile.REFILE, atTheSighting(HoldWhileJoining(), nowMs = 1_000L, joining = false))
    }

    @Test
    fun `Android is not asked for its networks when no refiling is due`() {
        val calls = IntArray(1)
        // The sighting is older than the loss: [DashJoin.refileAfterLoss] says no, and joiningNow would only cost a scan of networks.
        val decision = DashJoin.refileAfterLossHeld(
            false, lostAtUs = 100 * sec, sightingStampUs = 90 * sec, nowUs = 110 * sec, requestAgeMs = 5_000L, inPause = false,
            sinceLastRefileMs = null, hold = HoldWhileJoining(), nowMs = 0L, joiningNow = { calls[0]++; true },
        )
        assertEquals(DashJoin.LossRefile.NO, decision)
        assertEquals(0, calls[0])
    }

    @Test
    fun `the first approval of a network is never refiled, held or not`() {
        val decision = DashJoin.refileAfterLossHeld(
            true, 0L, 93 * sec, 96 * sec, 4_814L, false, null, HoldWhileJoining(), 0L, joiningNow = { true },
        )
        assertEquals(DashJoin.LossRefile.NO, decision)
    }

    @Test
    fun `the hold starts over when the Wi-Fi is not joined any more, so a long watch holds the next join too`() {
        val hold = HoldWhileJoining()
        assertTrue(hold.holds(true, 0L).holds)
        assertFalse("past the hold", hold.holds(true, DashJoin.HOLD_WHILE_JOINING_MS).holds)
        assertFalse("not joined: nothing to hold", hold.holds(false, 10_000L).holds)
        val again = hold.holds(true, 20_000L)
        assertTrue(again.holds)
        assertTrue("a new join says so in the log", again.began)
    }

    @Test
    fun `the hold says it began only the first time of a join`() {
        val hold = HoldWhileJoining()
        assertTrue(hold.holds(true, 0L).began)
        assertFalse(hold.holds(true, 2_000L).began)
        assertFalse(hold.holds(false, 3_000L).began)
    }

    // ── A network Android does not know is not used ───────────────────────────────────────────────

    @Test
    fun `a network is usable only when Android still has its capabilities and they say Wi-Fi`() {
        assertTrue(JoinedNetwork.usable(capsKnown = true, wifiTransport = true))
        assertFalse("a network that is gone (getNetworkCapabilities is null)", JoinedNetwork.usable(capsKnown = false, wifiTransport = false))
        assertFalse(JoinedNetwork.usable(capsKnown = true, wifiTransport = false))
        assertFalse(JoinedNetwork.usable(capsKnown = false, wifiTransport = true))
    }

    @Test
    fun `the check after the network is taken comes before the sockets would be bound and fail`() {
        // 47 ms of life in the log; the check must fall inside what a rider notices as one reconnection, and well before the 30 s retry.
        assertTrue(JoinedNetwork.CHECK_AFTER_MS in 500L..5_000L)
    }

    @Test
    fun `the line for a dead network says which network and when it was found out`() {
        assertEquals(
            "the already-connected Wi-Fi (network 454) is not known to Android any more (1500 ms after it was taken): not used, treated as lost",
            JoinedNetwork.deadLine(454, "1500 ms after it was taken"),
        )
    }

    // ── EPERM on a network: drop it, ask again, back off if it happens again ──────────────────────

    @Test
    fun `the first refusal asks for a network again at once`() {
        val v = BindRefusals().refused("454")
        assertEquals(BindRefusals.Step.ASK_AGAIN, v.step)
        assertEquals(1, v.inARow)
        assertEquals(0L, v.backoffMs)
        assertFalse(v.sameNetwork)
    }

    @Test
    fun `a second refusal in a row, on the new network, backs off instead of asking again at once`() {
        val refusals = BindRefusals()
        refusals.refused("454")
        val v = refusals.refused("455")
        assertEquals(BindRefusals.Step.BACK_OFF, v.step)
        assertEquals(2, v.inARow)
        assertEquals(BindRefusals.BACKOFF_BASE_MS, v.backoffMs)
        assertFalse("another network", v.sameNetwork)
    }

    @Test
    fun `the same network refused again is said so, and it backs off too`() {
        val refusals = BindRefusals()
        refusals.refused("454")
        val v = refusals.refused("454")
        assertTrue(v.sameNetwork)
        assertEquals(BindRefusals.Step.BACK_OFF, v.step)
    }

    @Test
    fun `the back off doubles and stops at the ceiling, however many refusals`() {
        val refusals = BindRefusals()
        val waits = (1..8).map { refusals.refused("n$it").backoffMs }
        assertEquals(listOf(0L, 15_000L, 30_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L), waits)
        assertEquals(BindRefusals.BACKOFF_MAX_MS, BindRefusals.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun `a bind that works starts the count over`() {
        val refusals = BindRefusals()
        refusals.refused("454")
        refusals.refused("455")
        refusals.bound()
        val v = refusals.refused("456")
        assertEquals(BindRefusals.Step.ASK_AGAIN, v.step)
        assertEquals(1, v.inARow)
        assertFalse("the memory of the last network goes too", refusals.also { it.bound() }.refused("456").sameNetwork)
    }

    @Test
    fun `the lines tell the network, the count and what REau does`() {
        val refusals = BindRefusals()
        assertEquals(
            "binding the socket to network 454 was refused (EPERM, #1 in a row): the network is dropped and asked for again",
            refusals.refused(454).line(454),
        )
        assertEquals(
            "binding the socket to network 454 was refused (EPERM, #2 in a row, the same network again): the network is dropped and not asked for again for 15 s",
            refusals.refused(454).line(454),
        )
    }

    // ── The whole sequence of 31/10/2026 ───────────────────────────────────────────────────────────

    @Test
    fun `the sequence of the log ends in a new request, not in ten sockets on the same dead network`() {
        // 10:06:56.137 the dash is back in the scan; the Wi-Fi it joins is the request's own: held, the request is answered.
        val hold = HoldWhileJoining()
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheSighting(hold, nowMs = 136_137L, joining = true))
        // Were that network found dead anyway (the system took it down), it is not used and is treated as lost...
        assertFalse(JoinedNetwork.usable(capsKnown = false, wifiTransport = false))
        // ...and if a socket is still refused on it, it is dropped and asked for again, once, and never handed back at 30 s.
        val refusals = BindRefusals()
        assertEquals(BindRefusals.Step.ASK_AGAIN, refusals.refused(454).step)
        val second = refusals.refused(454)
        assertEquals(BindRefusals.Step.BACK_OFF, second.step)
        assertNotEquals("the pause is the manager's own, not the projection's 30 s of the log", 30_000L, second.backoffMs)
    }

    // ── Ride log of 06/11/2026, 17:47:21: the network the phone joined by itself is refused, the dash is tried 30 s later ──

    @Test
    fun `the refusal of 06-11 is the first in a row, on a network of the phone's, and says whose it was`() {
        val refusals = BindRefusals()
        val v = refusals.refused(274, phoneJoined = true)
        assertEquals("the first one is asked for again at once: it was not a back off that cost the 30 s", BindRefusals.Step.ASK_AGAIN, v.step)
        assertEquals(1, v.inARow)
        val owner = NetworkOwner.words(joinedByPhone = true, request = 39, notRestricted = false, validated = false)
        assertEquals(
            "binding the socket to network 274 was refused (EPERM, #1 in a row, joined by the phone itself (a suggestion or a saved network), " +
                "not the answer to REau's request #39; not-restricted=false, validated=false): the network is dropped and asked for again",
            v.line(274, owner),
        )
    }

    @Test
    fun `the owner of a network of REau's own request, and what is not known`() {
        assertEquals("the answer to REau's request #40; not-restricted=true, validated=?", NetworkOwner.words(false, 40, true, null))
        assertEquals(
            "joined by the phone itself (a suggestion or a saved network), not the answer to REau's request #7; not-restricted=?, validated=?",
            NetworkOwner.words(true, 7),
        )
    }

    @Test
    fun `after the refusal, with another network up two seconds on, the projection is told to try the dash now`() {
        assertEquals(2_000L, RefusalRetry.AFTER_MS)
        // 17:47:21.276: network 297 was up 15 ms after the refusal of 274 (which is out of "already connected" for good).
        assertTrue(RefusalRetry.due(wantConnected = true, superseded = false, connected = true, networkRefused = false))
        assertEquals(
            "network 297 took the place of the refused one 2000 ms ago and is still up: the projection is told, it tries the dash now instead of waiting",
            RefusalRetry.line(297, RefusalRetry.AFTER_MS),
        )
    }

    @Test
    fun `the projection is not told when the rider switched off, another refusal came, nothing is up or the network up is a refused one`() {
        assertFalse(RefusalRetry.due(wantConnected = false, superseded = false, connected = true, networkRefused = false))
        assertFalse(RefusalRetry.due(wantConnected = true, superseded = true, connected = true, networkRefused = false))
        assertFalse("the request is still unanswered: its answer tells the projection", RefusalRetry.due(true, false, connected = false, networkRefused = false))
        assertFalse(RefusalRetry.due(wantConnected = true, superseded = false, connected = true, networkRefused = true))
    }

    @Test
    fun `a refusal of a network the phone joined, after another refusal, earns one more quick try, a different network each`() {
        val refusals = BindRefusals()
        assertEquals(BindRefusals.Step.ASK_AGAIN, refusals.refused(274, phoneJoined = true).step)
        val second = refusals.refused(297, phoneJoined = true)
        assertEquals(BindRefusals.Step.ASK_AGAIN, second.step)
        assertEquals(2, second.inARow)
        val third = refusals.refused(301, phoneJoined = true)
        assertEquals("the quick tries are bounded", BindRefusals.Step.BACK_OFF, third.step)
        assertEquals(30_000L, third.backoffMs)
    }

    @Test
    fun `the loop of 31-10 stays contained, the same network twice and REau's own requests wait as before`() {
        val sameNetwork = BindRefusals()
        sameNetwork.refused(454, phoneJoined = true)
        val again = sameNetwork.refused(454, phoneJoined = true)
        assertEquals("the same network again is never a quick try", BindRefusals.Step.BACK_OFF, again.step)
        assertEquals(BindRefusals.BACKOFF_BASE_MS, again.backoffMs)

        val own = BindRefusals()
        own.refused(274, phoneJoined = true)
        val ownRefused = own.refused(297, phoneJoined = false)
        assertEquals("a network of REau's own request refused after a first refusal waits 15 s", BindRefusals.Step.BACK_OFF, ownRefused.step)

        val forever = BindRefusals()
        val steps = (1..8).map { forever.refused("n$it", phoneJoined = true).step }
        assertEquals(2, steps.count { it == BindRefusals.Step.ASK_AGAIN })
        assertEquals(6, steps.count { it == BindRefusals.Step.BACK_OFF })
    }

    @Test
    fun `a bind that works gives the quick tries back`() {
        val refusals = BindRefusals()
        refusals.refused(1, phoneJoined = true)
        refusals.refused(2, phoneJoined = true)
        refusals.bound()
        assertEquals(BindRefusals.Step.ASK_AGAIN, refusals.refused(3, phoneJoined = true).step)
        assertEquals("and the second quick one is there again", BindRefusals.Step.ASK_AGAIN, refusals.refused(4, phoneJoined = true).step)
    }
}
