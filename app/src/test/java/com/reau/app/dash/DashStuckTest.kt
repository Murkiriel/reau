package com.reau.app.dash

import com.reau.app.dash.DashWifiManager.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dash's Wi-Fi can stop answering mid-ride and, for minutes, stay on the air on the same channel while refusing
 * every join (deauth reason 15, the four-way handshake), with the factory password that had just worked. Android gives
 * up on each request after 5-12 s of a 30 s wait, and a "rede não encontrada ou senha errada" each time would be wrong:
 * only a bike power-off clears a dash in that state.
 *
 * And the same drop asked for the network twice: the request the link loss scheduled 4 s ahead went out after the
 * projection's own, 1.4 s later, and cancelled it half-way.
 */
class DashStuckTest {
    private val timeout = 30_000

    @Test
    fun `a dash that refuses the join after it worked in this projection is a stuck dash, the first time`() {
        // 5723 ms after the request, scan unreadable in the background.
        assertEquals(Refusal.DASH_STUCK, DashWifiManager.refusal(seenInScan = null, tookMs = 5_723, timeoutMs = timeout, joinedBefore = true, earlyInARow = 1))
        assertEquals(Refusal.DASH_STUCK, DashWifiManager.refusal(seenInScan = true, tookMs = 9_550, timeoutMs = timeout, joinedBefore = true, earlyInARow = 1))
    }

    @Test
    fun `with no join yet, three quick refusals in a row are a stuck dash, fewer are not`() {
        // After REau was restarted it had no join of its own to go by.
        assertEquals(Refusal.UNKNOWN, DashWifiManager.refusal(seenInScan = null, tookMs = 9_550, timeoutMs = timeout, joinedBefore = false, earlyInARow = 1))
        assertEquals(Refusal.SEEN_NOT_JOINED, DashWifiManager.refusal(seenInScan = true, tookMs = 10_407, timeoutMs = timeout, joinedBefore = false, earlyInARow = 2))
        assertEquals(Refusal.DASH_STUCK, DashWifiManager.refusal(seenInScan = true, tookMs = 6_000, timeoutMs = timeout, joinedBefore = false, earlyInARow = 3))
    }

    @Test
    fun `a dash missing from the scan is not there, whatever came before, and the log says whether it had been there`() {
        // The bike turned off: no access point at all, nothing to power-cycle.
        assertEquals(Refusal.GONE_AFTER_JOIN, DashWifiManager.refusal(seenInScan = false, tookMs = 5_000, timeoutMs = timeout, joinedBefore = true, earlyInARow = 5))
        // Never joined in this projection: it is just not found.
        assertEquals(Refusal.NOT_FOUND, DashWifiManager.refusal(seenInScan = false, tookMs = 30_000, timeoutMs = timeout, joinedBefore = false, earlyInARow = 0))
        assertEquals(Refusal.NOT_FOUND, DashWifiManager.refusal(seenInScan = false, tookMs = 5_000, timeoutMs = timeout, joinedBefore = false, earlyInARow = 5))
    }

    @Test
    fun `the rider reads the same words for a dash that went away as for one never found`() {
        assertEquals(Refusal.NOT_FOUND.message("RE_AB12_345678"), Refusal.GONE_AFTER_JOIN.message("RE_AB12_345678"))
    }

    @Test
    fun `a request that ran out the clock was not refused by the dash`() {
        // No answer at all (out of range, the network picker left open): the old messages stand.
        assertEquals(Refusal.UNKNOWN, DashWifiManager.refusal(seenInScan = null, tookMs = 30_000, timeoutMs = timeout, joinedBefore = true, earlyInARow = 0))
        assertEquals(Refusal.SEEN_NOT_JOINED, DashWifiManager.refusal(seenInScan = true, tookMs = 15_000, timeoutMs = timeout, joinedBefore = true, earlyInARow = 0))
        assertEquals("just under half the wait is quick", Refusal.DASH_STUCK,
            DashWifiManager.refusal(seenInScan = true, tookMs = 14_999, timeoutMs = timeout, joinedBefore = true, earlyInARow = 1))
    }

    @Test
    fun `quick refusals are counted in a row and a slow one starts over`() {
        assertEquals(1, DashWifiManager.earlyInARow(previous = 0, tookMs = 5_723, timeoutMs = timeout))
        assertEquals(3, DashWifiManager.earlyInARow(previous = 2, tookMs = 6_003, timeoutMs = timeout))
        assertEquals(0, DashWifiManager.earlyInARow(previous = 2, tookMs = 30_000, timeoutMs = timeout))
    }

    @Test
    fun `the reconnect a link loss scheduled does not go out after a newer request`() {
        // Scheduled as request 7; the projection's request 8 went out 2 s later.
        assertFalse(DashWifiManager.reconnectDue(wantConnected = true, scheduledAt = 7, latestRequest = 8))
        assertTrue(DashWifiManager.reconnectDue(wantConnected = true, scheduledAt = 7, latestRequest = 7))
        assertFalse(DashWifiManager.reconnectDue(wantConnected = false, scheduledAt = 7, latestRequest = 7))
    }
}
