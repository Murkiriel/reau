package com.reau.app.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 22, item 3: the dash showing up in the scan in the pause between two requests of a network already approved cuts the pause.
 * Ride log of 01/11/2026: the dash was in the scan at 18:45:57, the next request left at 18:46:08.7, the end of a 30 s pause.
 */
class DashJoinPauseCutTest {
    private fun cuts(
        firstApproval: Boolean = false,
        seenWhenFailed: Boolean = false,
        seenNow: Boolean = true,
        sinceFailureMs: Long = DashJoin.PAUSE_CUT_MIN_GAP_MS,
        sinceLastCutMs: Long? = null,
    ) = DashJoin.cutsPauseOnSighting(firstApproval, seenWhenFailed, seenNow, sinceFailureMs, sinceLastCutMs)

    @Test
    fun `a dash that was out of the scan when the request failed and shows up cuts the pause`() {
        assertTrue(cuts())
        assertTrue("and later in the pause as well", cuts(sinceFailureMs = 25_000))
    }

    @Test
    fun `a dash not in the scan yet cuts nothing`() {
        assertFalse(cuts(seenNow = false))
    }

    @Test
    fun `a dash that was in the scan when the request failed is there and refusing, the pause is the controller's`() {
        assertFalse(cuts(seenWhenFailed = true))
        assertFalse(cuts(seenWhenFailed = true, sinceFailureMs = 25_000))
    }

    @Test
    fun `the first approval of a network is the other watch's, never this one`() {
        assertFalse(cuts(firstApproval = true))
    }

    @Test
    fun `not before the least gap since the failure`() {
        assertFalse(cuts(sinceFailureMs = DashJoin.PAUSE_CUT_MIN_GAP_MS - 1))
        assertTrue(cuts(sinceFailureMs = DashJoin.PAUSE_CUT_MIN_GAP_MS))
    }

    @Test
    fun `a pause cut lately is not followed by another until the interval is over, so a flickering dash is no loop of requests`() {
        assertFalse(cuts(sinceLastCutMs = DashJoin.PAUSE_CUT_EVERY_MS - 1))
        assertTrue(cuts(sinceLastCutMs = DashJoin.PAUSE_CUT_EVERY_MS))
    }

    @Test
    fun `the least gap can be given, as the link's test does`() {
        assertFalse(DashJoin.cutsPauseOnSighting(false, false, true, sinceFailureMs = 39, sinceLastCutMs = null, minGapMs = 40))
        assertTrue(DashJoin.cutsPauseOnSighting(false, false, true, sinceFailureMs = 40, sinceLastCutMs = null, minGapMs = 40))
    }

    @Test
    fun `the least gap is the pace of the machine's first wait and the interval between cuts is longer`() {
        assertTrue(DashJoin.PAUSE_CUT_MIN_GAP_MS <= 2_000)
        assertTrue(DashJoin.PAUSE_CUT_EVERY_MS > DashJoin.PAUSE_CUT_MIN_GAP_MS)
    }
}
