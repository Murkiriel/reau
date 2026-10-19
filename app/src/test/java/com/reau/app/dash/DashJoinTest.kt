package com.reau.app.dash

import com.reau.app.data.KeystoreBackedPrefs
import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import com.reau.app.data.KeystoreBackedPrefsTest.SoftCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A network the rider never let REau join makes Android stop to ask them; that request waits long enough for the tap.
 * Every other request keeps the waits it always had (the first of a session 30 s, a reconnection 10 s).
 */
class DashJoinTest {
    private val backing = FakePrefs()
    private fun pairing() = DashPairing(KeystoreBackedPrefs(backing, SoftCipher()))

    @Test
    fun `a network never joined gets two minutes for the rider's tap`() {
        assertEquals(120_000, DashWifiManager.timeoutForAttempt(everConnected = false, firstApproval = true))
        assertEquals(120_000, DashJoin.FIRST_APPROVAL_TIMEOUT_MS)
    }

    @Test
    fun `a network already approved keeps the 30 s of a first request and the 10 s of a reconnection`() {
        assertEquals(30_000, DashWifiManager.timeoutForAttempt(everConnected = false, firstApproval = false))
        assertEquals(30_000, DashWifiManager.timeoutForAttempt(everConnected = false))
        assertEquals(10_000, DashWifiManager.timeoutForAttempt(everConnected = true, firstApproval = false))
    }

    @Test
    fun `a reconnection is never stretched, even if the memory says the network was not approved`() {
        assertEquals(10_000, DashWifiManager.timeoutForAttempt(everConnected = true, firstApproval = true))
    }

    @Test
    fun `nothing joined before means the first approval, whether asked by the exact name or the prefix`() {
        assertTrue(DashJoin.needsApproval("", "RE_AB12_345678", wantedIsPrefix = false))
        assertTrue(DashJoin.needsApproval("", "RE_", wantedIsPrefix = true))
        assertTrue(DashJoin.needsApproval("  ", "RE_", wantedIsPrefix = true))
    }

    @Test
    fun `the same network joined before is approved, by the exact name or by the prefix`() {
        assertFalse(DashJoin.needsApproval("RE_AB12_345678", "RE_AB12_345678", wantedIsPrefix = false))
        assertFalse(DashJoin.needsApproval("RE_AB12_345678", "RE_", wantedIsPrefix = true))
    }

    @Test
    fun `another dash than the one joined is a first approval again`() {
        assertTrue(DashJoin.needsApproval("RE_AB12_345678", "RE_ZZ99_000001", wantedIsPrefix = false))
        assertTrue(DashJoin.needsApproval("RE_AB12_345678", "XX_", wantedIsPrefix = true))
    }

    @Test
    fun `without a memory nothing is assumed and the old waits stay`() {
        assertFalse(DashJoin.needsApproval(null, "RE_AB12_345678", wantedIsPrefix = false))
        assertFalse(DashJoin.needsApproval(null, "RE_", wantedIsPrefix = true))
    }

    @Test
    fun `only a whole network name is worth keeping as joined`() {
        assertTrue(DashJoin.worthKeeping("RE_AB12_345678", "RE_", pendingIsPrefix = true))
        assertTrue(DashJoin.worthKeeping("RE_AB12_345678", "RE_AB12_345678", pendingIsPrefix = false))
        assertFalse("the bare prefix a prefix request was made with", DashJoin.worthKeeping("RE_", "RE_", pendingIsPrefix = true))
        assertFalse(DashJoin.worthKeeping("", "RE_", pendingIsPrefix = true))
    }

    // ── a sighting in the scan counts only while recent (the clock is a parameter: microseconds since boot) ─────

    private val nowUs = 1_000_000_000L // 1000 s after boot
    private fun sighting(agoMs: Long, ssid: String = "RE_AB12_345678") = ScanSeen(ssid, -60, 2437, nowUs - agoMs * 1_000)

    @Test
    fun `a dash seen a moment ago is there`() {
        assertTrue(DashJoin.seenRecently(listOf(sighting(agoMs = 2_000)), "RE_AB12_345678", false, nowUs))
        assertTrue(DashJoin.seenRecently(listOf(sighting(agoMs = 2_000)), "RE_", true, nowUs))
    }

    @Test
    fun `a dash seen up to the validity ago is still there, one millisecond past it is not`() {
        assertEquals(60_000L, DashJoin.SIGHTING_FRESH_MS)
        assertTrue(DashJoin.seenRecently(listOf(sighting(agoMs = DashJoin.SIGHTING_FRESH_MS)), "RE_", true, nowUs))
        assertFalse(DashJoin.seenRecently(listOf(sighting(agoMs = DashJoin.SIGHTING_FRESH_MS + 1)), "RE_", true, nowUs))
    }

    @Test
    fun `a dash left in the scan list for minutes after the bike went off is not there`() {
        assertFalse(DashJoin.seenRecently(listOf(sighting(agoMs = 5 * 60_000)), "RE_AB12_345678", false, nowUs))
    }

    @Test
    fun `a fresh sighting of another network says nothing of the dash`() {
        val others = listOf(sighting(agoMs = 1_000, ssid = "Casa"), sighting(agoMs = 1_000, ssid = "RE_ZZ99_000001"))
        assertFalse(DashJoin.seenRecently(others, "RE_AB12_345678", false, nowUs))
        assertTrue(DashJoin.seenRecently(others, "RE_", true, nowUs))
    }

    @Test
    fun `one fresh sighting among old ones is enough`() {
        val both = listOf(sighting(agoMs = 10 * 60_000), sighting(agoMs = 3_000, ssid = "RE_ZZ99_000001"))
        assertTrue(DashJoin.seenRecently(both, "RE_", true, nowUs))
    }

    @Test
    fun `an unreadable or empty scan has no sighting`() {
        assertFalse(DashJoin.seenRecently(null, "RE_", true, nowUs))
        assertFalse(DashJoin.seenRecently(emptyList(), "RE_", true, nowUs))
    }

    @Test
    fun `a timestamp that cannot be right does not hold the dash back`() {
        assertTrue("zero", DashJoin.seenRecently(listOf(ScanSeen("RE_AB12_345678", -60, 2437, 0)), "RE_", true, nowUs))
        assertTrue("negative", DashJoin.seenRecently(listOf(ScanSeen("RE_AB12_345678", -60, 2437, -5)), "RE_", true, nowUs))
        assertTrue("in the future", DashJoin.seenRecently(listOf(ScanSeen("RE_AB12_345678", -60, 2437, nowUs + 1)), "RE_", true, nowUs))
    }

    @Test
    fun `a fresh install has joined nothing`() {
        assertEquals("", pairing().joinedSsid)
        assertTrue(DashJoin.needsApproval(pairing().joinedSsid, "RE_", wantedIsPrefix = true))
    }

    @Test
    fun `the joined network is kept across restarts and stops being a first approval`() {
        pairing().joinedSsid = "RE_AB12_345678"

        val again = pairing()

        assertEquals("RE_AB12_345678", again.joinedSsid)
        assertFalse(DashJoin.needsApproval(again.joinedSsid, "RE_AB12_345678", wantedIsPrefix = false))
    }

    @Test
    fun `learning or typing the dash's name is not joining it`() {
        val p = pairing().apply { ssid = "RE_AB12_345678" }

        assertEquals("", p.joinedSsid)
        assertTrue(DashJoin.needsApproval(p.joinedSsid, p.ssid, wantedIsPrefix = false))
    }

    @Test
    fun `forgetting the dash forgets that it was joined`() {
        val p = pairing().apply {
            ssid = "RE_AB12_345678"
            joinedSsid = "RE_AB12_345678"
            password = "s3nha"
        }

        p.forget()

        assertEquals("", p.joinedSsid)
        assertEquals("", p.ssid)
        assertEquals("s3nha", p.password)
        assertEquals("", pairing().joinedSsid)
        assertTrue(DashJoin.needsApproval(pairing().joinedSsid, "RE_AB12_345678", wantedIsPrefix = false))
    }
}
