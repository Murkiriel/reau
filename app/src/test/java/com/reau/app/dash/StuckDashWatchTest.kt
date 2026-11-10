package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride log of 01/11/2026, 19:12:44 to 19:16:41: the dash spoke for 26 minutes, went quiet, and stayed in the scan at -47 to -63 dBm while 23 requests
 * in a row ended unavailable in 6 s each; only the rider's ignition off and on brought it back. A dash that is switched off leaves the scan.
 *
 * This is the mid-ride detection ([DashStuck], [StuckDashWatch]); [DashStuckTest] keeps the older one, [DashWifiManager.Refusal.DASH_STUCK] of the initial connect.
 */
class StuckDashWatchTest {
    private val sec = 1_000_000L
    private fun dash(level: Int, stampSec: Long, name: String = "RE_AB12_345678") = ScanSeen(name, level, 2422, stampSec * sec)
    private val others = listOf(ScanSeen("Casa", -40, 2437, 99 * sec), ScanSeen("Vizinho", -70, 5180, 99 * sec))

    private fun sighting(seen: List<ScanSeen>?, nowSec: Long = 100) = DashStuck.sighting(seen, "RE_", true, nowSec * sec)

    // ── What the scan says ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the dash seen a few seconds ago at a good signal counts`() {
        assertEquals(DashStuck.Sighting.GOOD, sighting(others + dash(-58, 97)))
        assertEquals("-75 dBm is still good", DashStuck.Sighting.GOOD, sighting(others + dash(-75, 97)))
        assertEquals("15 s old is still there", DashStuck.Sighting.GOOD, sighting(others + dash(-58, 85)))
    }

    @Test
    fun `a dash that is not in the scan is gone, the bike is off or away`() {
        assertEquals(DashStuck.Sighting.GONE, sighting(others))
    }

    @Test
    fun `an old entry the scan list keeps for a switched off dash is gone too`() {
        assertEquals(DashStuck.Sighting.GONE, sighting(others + dash(-50, 100 - DashJoin.SIGHTING_FRESH_MS / 1_000 - 1)))
    }

    @Test
    fun `a weak signal, a middling age or a scan that says nothing neither count nor start over`() {
        assertEquals("weak", DashStuck.Sighting.UNKNOWN, sighting(others + dash(-80, 98)))
        assertEquals("16 s old but under a minute", DashStuck.Sighting.UNKNOWN, sighting(others + dash(-50, 84)))
        assertEquals("unreadable", DashStuck.Sighting.UNKNOWN, sighting(null))
        assertEquals("empty, as a background app is given", DashStuck.Sighting.UNKNOWN, sighting(emptyList()))
    }

    @Test
    fun `with several dashes in sight the best one decides`() {
        assertEquals(DashStuck.Sighting.GOOD, sighting(others + dash(-85, 98, "RE_ZZ99_000000") + dash(-60, 98)))
    }

    // ── The decision over an absence ───────────────────────────────────────────────────────────────

    private fun tries(watch: StuckDashWatch, startMs: Long, everyMs: Long, count: Int, sighting: DashStuck.Sighting = DashStuck.Sighting.GOOD) =
        (1..count).map { watch.failedTry(startMs + it * everyMs, sighting) }

    @Test
    fun `the log of 01 11 is a hung dash after three tries and half a minute`() {
        val watch = StuckDashWatch()
        watch.lost(nowMs = 1_000L, dashSpoke = true)
        // Tries about every 10 s (6 s of request and 4 s of pause): 19:12:55, 19:13:05, 19:13:16...
        val verdicts = tries(watch, 1_000L, 11_000L, 4)
        assertNull(verdicts[0])
        assertNull(verdicts[1])
        val third = verdicts[2]
        assertNotNull("the third try, 33 s after the loss", third)
        assertEquals(3, third!!.tries)
        assertEquals(33_000L, third.absentMs)
        assertNull("told once for an absence", verdicts[3])
    }

    @Test
    fun `three tries inside half a minute are not enough, a restarting dash does not take that long`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = true)
        assertNull(watch.failedTry(5_000L, DashStuck.Sighting.GOOD))
        assertNull(watch.failedTry(10_000L, DashStuck.Sighting.GOOD))
        assertNull(watch.failedTry(DashStuck.MIN_ABSENT_MS - 1, DashStuck.Sighting.GOOD))
        assertNotNull("the next one, past the half minute", watch.failedTry(DashStuck.MIN_ABSENT_MS, DashStuck.Sighting.GOOD))
    }

    @Test
    fun `a dash that leaves the scan starts the count over, it is the bike switched off`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = true)
        tries(watch, 0L, 10_000L, 2)
        assertNull(watch.failedTry(30_000L, DashStuck.Sighting.GONE))
        assertNull("two more are not three", tries(watch, 30_000L, 10_000L, 2).last())
        assertNotNull("the third after the dash came back to the scan", watch.failedTry(70_000L, DashStuck.Sighting.GOOD))
    }

    @Test
    fun `a dash that never left the scan, a moto off for good, is never told`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = true)
        assertTrue(tries(watch, 0L, 10_000L, 30, DashStuck.Sighting.GONE).all { it == null })
    }

    @Test
    fun `readings that say nothing neither count nor start over`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = true)
        tries(watch, 0L, 10_000L, 2)
        assertNull(watch.failedTry(30_000L, DashStuck.Sighting.UNKNOWN))
        assertNotNull(watch.failedTry(40_000L, DashStuck.Sighting.GOOD))
    }

    @Test
    fun `a dash that never spoke in this projection is not a hung one`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = false)
        assertTrue(tries(watch, 0L, 10_000L, 10).all { it == null })
    }

    @Test
    fun `no loss, no verdict`() {
        assertNull(StuckDashWatch().failedTry(100_000L, DashStuck.Sighting.GOOD))
    }

    @Test
    fun `the second loss of the same absence does not move its start`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = true)
        watch.lost(25_000L, dashSpoke = false)
        tries(watch, 0L, 10_000L, 2)
        assertNotNull("from the first loss, 30 s", watch.failedTry(30_000L, DashStuck.Sighting.GOOD))
    }

    @Test
    fun `back says whether the rider was told, and the next absence is a new one`() {
        val watch = StuckDashWatch()
        watch.lost(0L, dashSpoke = true)
        assertFalse("not told yet", watch.back())
        watch.lost(0L, dashSpoke = true)
        tries(watch, 0L, 11_000L, 3)
        assertTrue("told: the notice has to go", watch.back())
        assertFalse(watch.back())
        watch.lost(1_000_000L, dashSpoke = true)
        assertNull("a new count", watch.failedTry(1_011_000L, DashStuck.Sighting.GOOD))
        assertNull(StuckDashWatch().also { it.lost(0L, true); it.back() }.failedTry(100_000L, DashStuck.Sighting.GOOD))
    }

    @Test
    fun `the decision is made on the numbers proposed from the log`() {
        assertEquals(3, DashStuck.MIN_TRIES)
        assertEquals(30_000L, DashStuck.MIN_ABSENT_MS)
        assertEquals(-75, DashStuck.GOOD_SIGNAL_DBM)
        assertEquals(15_000L, DashStuck.FRESH_SIGHTING_MS)
    }

    @Test
    fun `the line of the log is the one the log reader looks for`() {
        assertEquals(
            "dash visible but refusing for 35 s (3 tries, -58 dBm) — dash Wi-Fi likely hung; rider told to cycle ignition",
            DashStuck.line(35_400L, 3, -58),
        )
        assertEquals(
            "dash visible but refusing for 35 s (3 tries) — dash Wi-Fi likely hung; rider told to cycle ignition",
            DashStuck.line(35_400L, 3, null),
        )
    }
}
