package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rule by which the Wi-Fi manager says whether it is getting a lost bike
 * network back by itself right now, for the projection's own retry to join instead of replacing it (a top-level
 * function of DashWifiManager.kt, next to the constants it reads).
 */
class DashWifiReconnectUnderWayTest {

    @Test
    fun `a recent reconnection after a loss, still wanted, is under way`() {
        assertTrue(reconnectUnderWay(wantConnected = true, everConnected = true, status = WifiConnStatus.REQUESTING, sinceMs = 0))
        assertTrue(reconnectUnderWay(wantConnected = true, everConnected = true, status = WifiConnStatus.REQUESTING, sinceMs = 13_000))
        assertTrue(reconnectUnderWay(wantConnected = true, everConnected = true, status = WifiConnStatus.REQUESTING, sinceMs = RECONNECT_WINDOW_MS))
    }

    @Test
    fun `one that went quiet for longer than a request and its pause is stuck, not under way`() {
        assertFalse(reconnectUnderWay(wantConnected = true, everConnected = true, status = WifiConnStatus.REQUESTING, sinceMs = RECONNECT_WINDOW_MS + 1))
        assertFalse("a clock that went backwards is not trusted either", reconnectUnderWay(wantConnected = true, everConnected = true, status = WifiConnStatus.REQUESTING, sinceMs = -1))
    }

    @Test
    fun `a first-time request is not a reconnection, nor a manager that was told to let go`() {
        assertFalse("never connected: the 30 s first-time request", reconnectUnderWay(wantConnected = true, everConnected = false, status = WifiConnStatus.REQUESTING, sinceMs = 0))
        assertFalse("disconnect() was called", reconnectUnderWay(wantConnected = false, everConnected = true, status = WifiConnStatus.REQUESTING, sinceMs = 0))
    }

    @Test
    fun `only while requesting`() {
        for (status in listOf(WifiConnStatus.IDLE, WifiConnStatus.CONNECTED, WifiConnStatus.ERROR)) {
            assertFalse("$status", reconnectUnderWay(wantConnected = true, everConnected = true, status = status, sinceMs = 0))
        }
    }

    @Test
    fun `the window is one request of the reconnection and its pause, with some slack`() {
        assertEquals(10_000L + 4_000L + 2_000L, RECONNECT_WINDOW_MS)
    }
}
