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
        assertNull(DashWifiManager.scanVerdict(emptyList(), "RE_", pendingPrefix = true))
        assertNull(DashWifiManager.scanVerdict(null, "RE_", pendingPrefix = true))
    }

    @Test
    fun `a scan with networks tells whether the dash is among them`() {
        assertEquals(false, DashWifiManager.scanVerdict(listOf("Casa", "Vizinho"), "RE_", pendingPrefix = true))
        assertEquals(true, DashWifiManager.scanVerdict(listOf("Casa", "RE_AB12_345678"), "RE_", pendingPrefix = true))
        assertEquals(true, DashWifiManager.scanVerdict(listOf("RE_AB12_345678"), "RE_AB12_345678", pendingPrefix = false))
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
    fun `a dash that answered and did not accept points at the SSID`() {
        // The name masked (DashNameInLogsTest).
        val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = "RE_AB12_345678")
        assertTrue(reason, "Check SSID matches 'RE_****78'" in reason)
    }
}
