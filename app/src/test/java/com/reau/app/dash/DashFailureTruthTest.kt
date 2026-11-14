package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app says must be what it knows.
 *
 * The scan: with the app in the background Android hands back an EMPTY scan, and that was read as "the dash is not
 * nearby" with the bike beside the phone; and a dash seen in the scan was blamed on the password, which Android
 * cannot tell from a declined network picker or a timeout.
 * The keyframe notice: the dash's keyframe-decoded notice (09 06 55) arriving before any video went out logged
 * "video accepted" and marked the first frame confirmed, which the video loop's watchdog trusts.
 */
class DashFailureTruthTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DashSession(scope)
    private val sock = DashSocket()

    @After
    fun tearDown() {
        sock.close()
        session.disconnect()
        scope.cancel()
    }

    // ── the scan ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty or unreadable scan says nothing about the dash`() {
        assertNull(DashSsid.scanVerdict(emptyList(), "RE_", pendingPrefix = true))
        assertNull(DashSsid.scanVerdict(null, "RE_", pendingPrefix = true))
    }

    @Test
    fun `a scan with networks tells whether the dash is among them`() {
        assertEquals(false, DashSsid.scanVerdict(listOf("Casa", "Vizinho"), "RE_", pendingPrefix = true))
        assertEquals(true, DashSsid.scanVerdict(listOf("Casa", "RE_AB12_345678"), "RE_", pendingPrefix = true))
        assertEquals(true, DashSsid.scanVerdict(listOf("RE_AB12_345678"), "RE_AB12_345678", pendingPrefix = false))
    }

    // ── the keyframe-decoded notice ────────────────────────────────────────────────────────────────────────────

    private val idrAck = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x09, 0x06, 0x00, 0x01, 0x55)

    @Test
    fun `a keyframe notice before any video does not confirm the first frame`() {
        session.dispatchIncoming(idrAck, sock)
        assertFalse("not streaming: nothing was sent to be decoded", session.firstFrameConfirmed)
        assertEquals("still counted as an ack, as before", 1L, session.decodedFrameCount)
    }

    @Test
    fun `only a notice while streaming confirms the first frame`() {
        assertTrue(DashSession.confirmsFirstFrame(DashState.STREAMING))
        for (s in DashState.entries - DashState.STREAMING) assertFalse(s.name, DashSession.confirmsFirstFrame(s))
    }

    // ── the authentication that runs out ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a dash that never answered is not blamed on the SSID`() {
        val reason = DashSession.authTimeoutReason(heardFromDash = false, ssid = "RE_AB12_345678")
        assertTrue(reason, "not a single packet" in reason)
        assertFalse(reason, "SSID" in reason)
    }

    @Test
    fun `a dash that answered says to check its screen type, one that stayed silent does not`() {
        val silent = DashSession.authTimeoutReason(heardFromDash = false, ssid = "RE_AB12_345678")
        assertFalse(silent, "Screen type > Digital" in silent)
        for (keySeen in listOf(true, false)) {
            val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678", keySeen = keySeen)
            assertTrue("keySeen=$keySeen: $reason", "Appearance > Screen type > Digital" in reason)
        }
    }

    @Test
    fun `a dash that answered and did not accept points at the SSID`() {
        // The name masked (DashNameInLogsTest).
        val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678")
        assertTrue(reason, "Check SSID matches 'RE_****78'" in reason)
    }

    // ── the key that never comes (20:24:02 on 19/10/2026: 15 s waiting, then a new attempt got the key in 50 ms) ───

    @Test
    fun `the burst goes out again only when the dash has not offered its key for three seconds`() {
        assertFalse(DashSession.burstDue(sinceLastBurstMs = 2_999, keySeen = false, resends = 0))
        assertTrue(DashSession.burstDue(sinceLastBurstMs = 3_000, keySeen = false, resends = 0))
        assertFalse("the dash is already in the key exchange", DashSession.burstDue(sinceLastBurstMs = 9_000, keySeen = true, resends = 0))
        assertTrue(DashSession.burstDue(sinceLastBurstMs = 3_000, keySeen = false, resends = DashSession.MAX_BURST_RESENDS - 1))
        assertFalse("the resends stop at the deadline", DashSession.burstDue(sinceLastBurstMs = 3_000, keySeen = false, resends = DashSession.MAX_BURST_RESENDS))
    }

    @Test
    fun `a dash that answered but never sent its key is not blamed on the SSID`() {
        val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678", keySeen = false)
        assertTrue(reason, "never sent its key" in reason)
        assertFalse(reason, "SSID" in reason)
    }

    @Test
    fun `a dash that sent its key and still did not accept points at the SSID as before`() {
        val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678", keySeen = true)
        assertTrue(reason, "Check SSID matches 'RE_****78'" in reason)
    }

    // ── the greeting the dash ignores (12:00:34 on 20/10/2026: the key came only with the second connect(), 17 s after the first) ──

    @Test
    fun `the burst is sent again every 3 seconds up to the deadline, 3 6 9 and 12 seconds`() {
        assertEquals(4, DashSession.MAX_BURST_RESENDS)
        var resends = 0
        val at = mutableListOf<Long>()
        var last = 0L
        for (ms in 0L..15_000L step 100) {
            if (DashSession.burstDue(ms - last, keySeen = false, resends)) {
                resends++
                last = ms
                at += ms
            }
        }
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 12_000L), at)
        assertFalse("and stops when the key shows", DashSession.burstDue(3_000, keySeen = true, resends = 2))
    }

    @Test
    fun `the dash's 10 s mark with no key asks for the burst at once, once, only while authenticating`() {
        assertTrue(DashSession.resendOnClockNotice(DashState.AUTHENTICATING, keySeen = false, alreadyDone = false))
        assertFalse("the key came: the exchange is on", DashSession.resendOnClockNotice(DashState.AUTHENTICATING, keySeen = true, alreadyDone = false))
        assertFalse("once a session", DashSession.resendOnClockNotice(DashState.AUTHENTICATING, keySeen = false, alreadyDone = true))
        for (s in DashState.entries - DashState.AUTHENTICATING) {
            assertFalse(s.name, DashSession.resendOnClockNotice(s, keySeen = false, alreadyDone = false))
        }
    }

    @Test
    fun `the failure says how many times the burst was sent and when the 10 s mark came`() {
        val reason = DashSession.authTimeoutReason(
            heardFromDash = true, ssid = "RE_AB12_345678", keySeen = false, resends = 5, noticeAfterMs = 10_200,
        )
        assertTrue(reason, "sent again 5 times" in reason)
        assertTrue(reason, "10 s mark (09 06 55) came 10 s after the first burst" in reason)
        val none = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678", keySeen = false, resends = 4)
        assertFalse(none, "10 s mark" in none)
    }

    // ── a weak link is not a wrong name (05/11/2026 07:13:21: -80 dBm, 3 packets in 15 s, and "Check SSID" for a dash that was on and right) ──

    @Test
    fun `a dash that answered over a weak signal is not blamed on the name, and the text says the signal and the packets`() {
        val reason = DashSession.authTimeoutReason(
            heardFromDash = true, ssid = "RE_AB12_345678", keySeen = true, rssiDbm = -80, packets = 3, waitedMs = 15_000,
        )
        assertTrue(reason, "weak" in reason && "-80 dBm, 3 packets in 15 s" in reason)
        assertTrue(reason, "too far" in reason)
        assertFalse(reason, "SSID" in reason || "Check" in reason || "Digital" in reason)
    }

    @Test
    fun `a weak link is told whether the key came or not`() {
        for (keySeen in listOf(true, false)) {
            val reason = DashSession.authTimeoutReason(
                heardFromDash = true, ssid = "RE_AB12_345678", keySeen = keySeen, rssiDbm = -78, packets = 40,
            )
            assertTrue("keySeen=$keySeen: $reason", "link is weak (-78 dBm)" in reason)
            assertFalse("keySeen=$keySeen: $reason", "SSID" in reason || "Digital" in reason || "never sent its key" in reason)
        }
    }

    @Test
    fun `too few packets alone make the link weak, with the signal unknown`() {
        val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678", rssiDbm = null, packets = 3, waitedMs = 15_000)
        assertTrue(reason, "link is weak (3 packets in 15 s)" in reason)
        assertFalse(reason, "dBm" in reason)
    }

    @Test
    fun `the weak signal is minus 75 dBm or under, and fewer than ten packets`() {
        assertTrue(DashSession.weakLink(-75, 100))
        assertTrue(DashSession.weakLink(-90, 100))
        assertFalse(DashSession.weakLink(-74, 100))
        assertFalse(DashSession.weakLink(null, 100))
        assertTrue(DashSession.weakLink(-60, 9))
        assertFalse(DashSession.weakLink(-60, 10))
        assertEquals(-75, DashSession.WEAK_SIGNAL_DBM)
        assertEquals(10, DashSession.FEW_PACKETS)
    }

    @Test
    fun `a good link that did not accept keeps pointing at the name, and a dash that never answered is not a weak link`() {
        val good = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678", rssiDbm = -52, packets = 60)
        assertTrue(good, "Check SSID matches 'RE_****78'" in good)
        val silent = DashSession.authTimeoutReason(heardFromDash = false, ssid = "RE_AB12_345678", rssiDbm = -85, packets = 0)
        assertTrue(silent, "not a single packet" in silent)
        assertFalse(silent, "weak" in silent)
    }
}
