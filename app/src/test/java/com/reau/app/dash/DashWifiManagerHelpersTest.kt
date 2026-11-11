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
        // A name of five to seven characters came out whole, or all but one or two characters: two characters of it now.
        for (short in listOf("RE_AB", "RE_AB1", "RE_AB12")) assertEquals(short, "RE**", DashWifiManager.maskSsid(short))
        assertEquals("eight characters: three and two", "RE_****23", DashWifiManager.maskSsid("RE_AB123"))
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
        assertEquals("scan empty [x]", scanEvidence(emptyList(), "RE_", true, now) { " [x]" })
        assertEquals("scan unreadable [x]", scanEvidence(null, "RE_", true, now) { " [x]" })
        val withNetworks = scanEvidence(listOf(ScanSeen("Home", -50, 2412, now)), "RE_", true, now) { error("a list with networks reads no state") }
        assertEquals("scan of 1 networks, the dash not among them (newest result 0 s old)", withNetworks)
    }

    @Test
    fun `the state that explains an empty list says the location type, the permission, the system switch and the importance`() {
        assertEquals(
            " [location type=on, permission=given, system location=on, process importance 125 (foreground service)]",
            emptyScanState(locationType = true, permission = true, systemLocationOn = true, importance = 125),
        )
        assertEquals(
            " [location type=off, permission=missing, system location=off, process importance 400 (cached)]",
            emptyScanState(locationType = false, permission = false, systemLocationOn = false, importance = 400),
        )
        assertEquals(
            " [location type=off, permission=given, system location=unknown, process importance 77]",
            emptyScanState(locationType = false, permission = true, systemLocationOn = null, importance = 77),
        )
        assertEquals(
            "scan empty [location type=off, permission=given, system location=on, process importance 125 (foreground service)]",
            scanEvidence(emptyList(), "RE_", true, now) { emptyScanState(false, true, true, 125) },
        )
    }

    @Test
    fun `the scan line says the dash is not among the networks seen`() {
        val seen = listOf(ScanSeen("Home", -50, 2412, now), ScanSeen("Cafe", -70, 5180, now))
        assertEquals("scan of 2 networks, the dash not among them (newest result 0 s old)", scanEvidence(seen, "RE_", true, now))
    }

    @Test
    fun `the scan line gives the dash's signal, band and how old the sighting is, with the name masked`() {
        val seen = listOf(
            ScanSeen("Home", -50, 2412, now),
            ScanSeen("RE_1234ABCD", -61, 2437, now - 95_000_000L),
        )
        assertEquals(
            "scan of 2 networks, the dash: 'RE_****CD' -61 dBm 2437 MHz seen 95 s ago (newest result 0 s old)",
            scanEvidence(seen, "RE_", true, now),
        )
        assertEquals("exact name: only that one", true, scanEvidence(seen, "RE_9999", false, now).contains("not among"))
    }

    @Test
    fun `a sighting stamped in the future never gives a negative age`() {
        val seen = listOf(ScanSeen("RE_1234ABCD", -61, 2437, now + 5_000_000L))
        assertTrue(scanEvidence(seen, "RE_", true, now).contains("seen 0 s ago"))
    }

    @Test
    fun `a request answered and lost afterwards is not told as let go unanswered`() {
        // The ride of 357 720 ms: the network came, was lost, the state went back to REQUESTING, then the request was let go.
        assertEquals(
            "released request #1 after 357720 ms (status=REQUESTING, answered=true, unanswered=false)",
            releaseLine(1, 357_720, WifiConnStatus.REQUESTING, answered = true),
        )
    }

    @Test
    fun `a request that expired without an answer is told as unanswered`() {
        assertEquals(
            "released request #3 after 30000 ms (status=REQUESTING, answered=false, unanswered=true)",
            releaseLine(3, 30_000, WifiConnStatus.REQUESTING, answered = false),
        )
        assertTrue(releaseLine(2, 800, WifiConnStatus.ERROR, answered = false).endsWith("unanswered=true)"))
    }
}
