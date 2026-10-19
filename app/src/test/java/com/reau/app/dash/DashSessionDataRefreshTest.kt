package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bike's data is asked of the dash when it connects (the initial burst) and again every 15 minutes while it stays
 * connected.
 */
class DashSessionDataRefreshTest {
    @Test
    fun `fifteen minutes are 900 ticks of the 1 Hz heartbeat`() {
        assertEquals(15 * 60, DashSession.DATA_REFRESH_TICKS)
    }

    @Test
    fun `it is asked at every 900th tick, connected, and never at the first one`() {
        assertFalse("the burst did the first ask", DashSession.dataRefreshDue(0, DashState.STREAMING))
        assertTrue(DashSession.dataRefreshDue(900, DashState.STREAMING))
        assertTrue(DashSession.dataRefreshDue(1_800, DashState.READY))
        assertFalse(DashSession.dataRefreshDue(899, DashState.STREAMING))
        assertFalse(DashSession.dataRefreshDue(901, DashState.STREAMING))
    }

    @Test
    fun `it is not asked while the dash is not connected`() {
        for (state in listOf(DashState.IDLE, DashState.CONNECTING, DashState.AUTHENTICATING, DashState.ERROR)) {
            assertFalse("$state", DashSession.dataRefreshDue(900, state))
        }
    }
}
