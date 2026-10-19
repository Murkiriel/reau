package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure decision logic pulled out of DashWifiManager's Android-framework-calling
 * methods (see its companion object) — the rest of the class (WifiNetworkSpecifier,
 * ConnectivityManager.requestNetwork) needs a real access point and isn't testable here.
 */
class DashWifiManagerHelpersTest {

    @Test
    fun `timeoutForAttempt uses the longer timeout for a fresh connect`() {
        assertEquals(30_000, DashWifiManager.timeoutForAttempt(everConnected = false))
    }

    @Test
    fun `timeoutForAttempt uses the shorter timeout once we've connected before`() {
        assertEquals(10_000, DashWifiManager.timeoutForAttempt(everConnected = true))
    }

    @Test
    fun `normalizeSsid strips the surrounding quotes Android adds`() {
        assertEquals("RE_1234ABCD", DashWifiManager.normalizeSsid("\"RE_1234ABCD\""))
    }

    @Test
    fun `normalizeSsid collapses the unknown-ssid sentinel to blank`() {
        assertEquals("", DashWifiManager.normalizeSsid("<unknown ssid>"))
    }

    @Test
    fun `normalizeSsid passes through an already-unquoted ssid unchanged`() {
        assertEquals("RE_1234ABCD", DashWifiManager.normalizeSsid("RE_1234ABCD"))
    }

    @Test
    fun `normalizeSsid treats null as blank`() {
        assertEquals("", DashWifiManager.normalizeSsid(null))
    }

    @Test
    fun `shouldRetryOnUnavailable retries only when we'd connected before AND still want to`() {
        assertTrue(DashWifiManager.shouldRetryOnUnavailable(everConnected = true, wantConnected = true))
        assertFalse(DashWifiManager.shouldRetryOnUnavailable(everConnected = true, wantConnected = false))
        assertFalse(DashWifiManager.shouldRetryOnUnavailable(everConnected = false, wantConnected = true))
        assertFalse(DashWifiManager.shouldRetryOnUnavailable(everConnected = false, wantConnected = false))
    }

    @Test
    fun `matchesPendingSsid requires an exact match when not prefix mode`() {
        assertTrue(DashWifiManager.matchesPendingSsid("RE_1234ABCD", "RE_1234ABCD", pendingPrefix = false))
        assertFalse(DashWifiManager.matchesPendingSsid("RE_1234ABCDX", "RE_1234ABCD", pendingPrefix = false))
    }

    @Test
    fun `matchesPendingSsid accepts any suffix in prefix mode`() {
        assertTrue(DashWifiManager.matchesPendingSsid("RE_1234ABCD", "RE_", pendingPrefix = true))
        assertFalse(DashWifiManager.matchesPendingSsid("MyHomeWifi", "RE_", pendingPrefix = true))
    }

    @Test
    fun `maskSsid keeps the shape but hides the value`() {
        assertEquals("RE_****CD", DashWifiManager.maskSsid("RE_1234ABCD"))
    }

    @Test
    fun `maskSsid handles a short ssid without going negative on length`() {
        assertEquals("RE**", DashWifiManager.maskSsid("RE_1"))
    }

    @Test
    fun `maskSsid of a blank ssid is blank, not a crash`() {
        assertEquals("", DashWifiManager.maskSsid(""))
    }

    @Test
    fun `candidateMatchesActive accepts a candidate with no readable ssid`() {
        assertTrue(DashWifiManager.candidateMatchesActive(candidateSsid = null, activeSsid = "RE_1234ABCD"))
    }

    @Test
    fun `candidateMatchesActive accepts a candidate whose ssid equals the active one`() {
        assertTrue(DashWifiManager.candidateMatchesActive(candidateSsid = "RE_1234ABCD", activeSsid = "RE_1234ABCD"))
    }

    @Test
    fun `candidateMatchesActive rejects a candidate with a different readable ssid`() {
        assertFalse(DashWifiManager.candidateMatchesActive(candidateSsid = "MyHomeWifi", activeSsid = "RE_1234ABCD"))
    }

    // ── the scan line of the ride log (no decision reads it) ───────────────────────────────────────────

    private val now = 600_000_000L // microseconds since boot: ten minutes

    @Test
    fun `the scan line says when the scan cannot be read or is empty`() {
        assertEquals("scan unreadable", scanEvidence(null, "RE_", true, now))
        assertEquals("scan empty", scanEvidence(emptyList(), "RE_", true, now))
    }

    @Test
    fun `the scan line says the dash is not among the networks seen`() {
        val seen = listOf(ScanSeen("Home", -50, 2412, now), ScanSeen("Cafe", -70, 5180, now))
        assertEquals("scan of 2 networks, the dash not among them", scanEvidence(seen, "RE_", true, now))
    }

    @Test
    fun `the scan line gives the dash's signal, band and how old the sighting is, with the name masked`() {
        val seen = listOf(
            ScanSeen("Home", -50, 2412, now),
            ScanSeen("RE_1234ABCD", -61, 2437, now - 95_000_000L),
        )
        assertEquals(
            "scan of 2 networks, the dash: 'RE_****CD' -61 dBm 2437 MHz seen 95 s ago",
            scanEvidence(seen, "RE_", true, now),
        )
        assertEquals("exact name: only that one", true, scanEvidence(seen, "RE_9999", false, now).contains("not among"))
    }

    @Test
    fun `a sighting stamped in the future never gives a negative age`() {
        val seen = listOf(ScanSeen("RE_1234ABCD", -61, 2437, now + 5_000_000L))
        assertTrue(scanEvidence(seen, "RE_", true, now).endsWith("seen 0 s ago"))
    }
}
