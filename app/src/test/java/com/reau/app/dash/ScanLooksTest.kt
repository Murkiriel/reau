package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines that let the reader of the ride log decide the empty-list test by itself (08/11/2026): the last
 * reading with networks and how old it is, whether the phone is joined to a Wi-Fi, the age of the newest result, and the look lines of the
 * watches. All log text; nothing here changes what the app does.
 */
class ScanLooksTest {
    private val nowUs = 500_000_000L // 500 s since boot
    private val nowMs = 500_000L

    private fun net(name: String, ageS: Long) = ScanSeen(name, -60, 2412, nowUs - ageS * 1_000_000L)

    // ── the trail: the last reading that had networks ──────────────────────────────────────────────────────────

    @Test
    fun `a trail with no reading yet says so`() {
        assertEquals("last non-empty reading: none yet", ScanTrail().describe(nowMs, nowUs))
    }

    @Test
    fun `the trail says how many networks the last reading had, how long ago, and how old its newest result is now`() {
        val trail = ScanTrail()
        trail.note(12, nowMs - 31_000, nowUs - 35_000_000L)
        assertEquals("last non-empty reading: 12 networks, 31 s ago, newest result 35 s old", trail.describe(nowMs, nowUs))
    }

    @Test
    fun `an empty reading does not replace the last one with networks`() {
        val trail = ScanTrail()
        trail.note(7, nowMs - 10_000, nowUs - 10_000_000L)
        trail.note(0, nowMs - 1_000, 0L)
        assertTrue(trail.describe(nowMs, nowUs), trail.describe(nowMs, nowUs).startsWith("last non-empty reading: 7 networks, 10 s ago"))
    }

    @Test
    fun `a newer reading with networks replaces the older one, and forget starts over`() {
        val trail = ScanTrail()
        trail.note(7, nowMs - 10_000, nowUs - 10_000_000L)
        trail.note(3, nowMs - 2_000, nowUs - 2_000_000L)
        assertEquals("last non-empty reading: 3 networks, 2 s ago, newest result 2 s old", trail.describe(nowMs, nowUs))
        trail.forget()
        assertEquals("last non-empty reading: none yet", trail.describe(nowMs, nowUs))
    }

    @Test
    fun `a reading whose results carry no stamp leaves the newest result out`() {
        val trail = ScanTrail()
        trail.note(2, nowMs - 4_000, 0L)
        assertEquals("last non-empty reading: 2 networks, 4 s ago", trail.describe(nowMs, nowUs))
    }

    // ── the phone joined to a Wi-Fi ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Wi-Fi the phone is joined to is no, the dash, another one or both`() {
        assertEquals("no", joinedWifiWords(0, dashAmongThem = false))
        assertEquals("other", joinedWifiWords(1, dashAmongThem = false))
        assertEquals("dash", joinedWifiWords(1, dashAmongThem = true))
        assertEquals("dash+other", joinedWifiWords(2, dashAmongThem = true))
        assertEquals("other", joinedWifiWords(2, dashAmongThem = false))
    }

    // ── the line of the scan ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty list says what the reader needs to tell no access from no network`() {
        val state = emptyScanState(
            locationType = true, permission = true, systemLocationOn = true, importance = 125,
            more = EmptyScanFacts(lastNonEmpty = "last non-empty reading: 12 networks, 31 s ago", wifiJoined = "no"),
        )
        assertEquals(
            " [location type=on, permission=given, system location=on, process importance 125 (foreground service), " +
                "last non-empty reading: 12 networks, 31 s ago, wifi joined=no]",
            state,
        )
        assertEquals(
            "without the new facts the text is the old one",
            " [location type=on, permission=given, system location=on, process importance 125 (foreground service)]",
            emptyScanState(locationType = true, permission = true, systemLocationOn = true, importance = 125),
        )
    }

    @Test
    fun `a list with networks says how old its newest result is, the dash in it or not`() {
        val without = scanEvidence(listOf(net("Home", 4), net("Cafe", 9)), "RE_", true, nowUs)
        assertEquals("scan of 2 networks, the dash not among them (newest result 4 s old)", without)
        val with = scanEvidence(listOf(net("Home", 2), net("RE_1234ABCD", 13)), "RE_", true, nowUs)
        assertEquals("scan of 2 networks, the dash: 'RE_****CD' -60 dBm 2412 MHz seen 13 s ago (newest result 2 s old)", with)
    }

    @Test
    fun `results without a stamp leave the newest result out of the line`() {
        val unstamped = listOf(ScanSeen("Home", -50, 2412, 0L))
        assertEquals("scan of 1 networks, the dash not among them", scanEvidence(unstamped, "RE_", true, nowUs))
        assertNull(newestAge(0L, nowUs))
        assertEquals(0L, newestStamp(emptyList()))
        assertEquals(nowUs - 3_000_000L, newestStamp(listOf(net("a", 9), net("b", 3), net("c", 5))))
    }

    // ── the looks of the watches ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a look says the dash came into the list with the age of its result, and a look that adds nothing says nothing`() {
        val log = ScanLookLog()
        val seen = listOf(net("Home", 3), net("RE_1234ABCD", 7))
        assertEquals(
            "scan look: the dash is in the list of 2 networks, its result 7 s old, newest result 3 s old",
            log.line(seen, "RE_", true, nowUs),
        )
        assertNull("the same list, looked at again", log.line(seen, "RE_", true, nowUs + 2_000_000L))
    }

    @Test
    fun `a look before the dash is there says the list and the age of its newest result, and a new scan is a new line`() {
        val log = ScanLookLog()
        assertEquals("scan look: the dash is not among 1 networks, newest result 5 s old", log.line(listOf(net("Home", 5)), "RE_", true, nowUs))
        assertNull(log.line(listOf(net("Home", 5)), "RE_", true, nowUs + 2_000_000L))
        assertEquals(
            "scan look: the dash is not among 1 networks, newest result 1 s old",
            log.line(listOf(ScanSeen("Home", -60, 2412, nowUs + 6_000_000L)), "RE_", true, nowUs + 7_000_000L),
        )
    }

    @Test
    fun `the dash leaving the list and the list becoming empty or unreadable are told`() {
        val log = ScanLookLog()
        assertTrue(log.line(listOf(net("RE_1234ABCD", 1)), "RE_", true, nowUs)!!.contains("the dash is in the list"))
        assertTrue(log.line(listOf(net("Home", 1)), "RE_", true, nowUs)!!.contains("the dash is not among"))
        assertEquals("scan look: the list is empty", log.line(emptyList(), "RE_", true, nowUs))
        assertEquals("scan look: the list cannot be read", log.line(null, "RE_", true, nowUs))
        assertNull(log.line(null, "RE_", true, nowUs))
    }

    @Test
    fun `the look lines are limited, and the last one says they stop`() {
        val log = ScanLookLog(maxLines = 3)
        val lines = (1..6).map { log.line(listOf(ScanSeen("Home", -60, 2412, nowUs + it * 1_000_000L)), "RE_", true, nowUs + it * 1_000_000L) }
        assertEquals(3, lines.take(3).count { it != null && it.startsWith("scan look: the dash is not among") })
        assertEquals("scan look: 3 lines written, the next looks of this session are not", lines[3])
        assertNull(lines[4])
        assertNull(lines[5])
    }

    @Test
    fun `the exact name is what the look compares, not any network`() {
        val log = ScanLookLog()
        assertFalse(log.line(listOf(net("RE_1234ABCD", 1)), "RE_9999", false, nowUs)!!.contains("the dash is in the list"))
    }
}
