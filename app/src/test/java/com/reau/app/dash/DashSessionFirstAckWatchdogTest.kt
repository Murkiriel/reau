package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DashSession.firstAckTimedOut] — the watchdog gap found in a ride log:
 * the dash WiFi cycled REQUESTING → unavailable → retry for over 6 minutes straight
 * (never reaching CONNECTED again after an initial blip), while the session's stale socket kept
 * accepting sendto() calls without throwing. _state sat in STREAMING the whole time with zero
 * feedback — the existing RX watchdog can never catch this because it's gated on
 * [DashSession] having ALREADY gotten a first decode ack (a link that worked and then died, not
 * one that never worked at all).
 */
class DashSessionFirstAckWatchdogTest {

    @Test
    fun `does not time out before streamingStartMs is even set`() {
        // streamingStartMs == 0L means STREAMING hasn't actually been entered yet (or this is a
        // fresh/reset session) — must never fire based on nowMs alone.
        assertFalse(DashSession.firstAckTimedOut(loggedFirstAck = false, streamingStartMs = 0L, nowMs = 999_999L, timeoutMs = 15_000L))
    }

    @Test
    fun `does not time out once the dash already acked a decoded frame`() {
        assertFalse(DashSession.firstAckTimedOut(loggedFirstAck = true, streamingStartMs = 1_000L, nowMs = 1_000_000L, timeoutMs = 15_000L))
    }

    @Test
    fun `does not time out while still within the grace window`() {
        assertFalse(DashSession.firstAckTimedOut(loggedFirstAck = false, streamingStartMs = 1_000L, nowMs = 1_000L + 14_999L, timeoutMs = 15_000L))
    }

    @Test
    fun `times out once the grace window has elapsed with no ack ever received`() {
        assertTrue(DashSession.firstAckTimedOut(loggedFirstAck = false, streamingStartMs = 1_000L, nowMs = 1_000L + 15_001L, timeoutMs = 15_000L))
    }

    @Test
    fun `the exact boundary (elapsed equals the timeout) does not yet count as timed out`() {
        assertFalse(DashSession.firstAckTimedOut(loggedFirstAck = false, streamingStartMs = 1_000L, nowMs = 1_000L + 15_000L, timeoutMs = 15_000L))
    }

    // ── a dash that answers but sends no 09 06 55 is showing the video ──

    @Test
    fun `no ack after 15 s and a silent dash is a dead link, as before`() {
        assertEquals(
            DashSession.Companion.FirstAck.DEAD,
            DashSession.firstAckVerdict(false, streamingStartMs = 1_000L, nowMs = 16_001L, timeoutMs = 15_000L, lastRxMs = 6_000L, rxSilenceMs = 4_000L),
        )
        assertEquals(
            "nothing heard at all",
            DashSession.Companion.FirstAck.DEAD,
            DashSession.firstAckVerdict(false, streamingStartMs = 1_000L, nowMs = 16_001L, timeoutMs = 15_000L, lastRxMs = 0L, rxSilenceMs = 4_000L),
        )
    }

    @Test
    fun `no ack after 15 s from a dash that still answers goes on, the dash is showing the video`() {
        // A ride: the map on the dash, heartbeats and buttons coming, and the session ended every 15 s for minutes.
        assertEquals(
            DashSession.Companion.FirstAck.ALIVE_WITHOUT_ACK,
            DashSession.firstAckVerdict(false, streamingStartMs = 1_000L, nowMs = 16_001L, timeoutMs = 15_000L, lastRxMs = 15_500L, rxSilenceMs = 4_000L),
        )
    }

    @Test
    fun `before 15 s, or with the ack, the watchdog waits`() {
        assertEquals(DashSession.Companion.FirstAck.WAIT, DashSession.firstAckVerdict(false, 1_000L, 10_000L, 15_000L, 0L, 4_000L))
        assertEquals(DashSession.Companion.FirstAck.WAIT, DashSession.firstAckVerdict(true, 1_000L, 99_000L, 15_000L, 0L, 4_000L))
    }
}
