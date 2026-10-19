package com.reau.app.dash

import com.reau.app.R
import com.reau.app.service.WifiDashLinkUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dash's Wi-Fi name ties a log to one bike, so every line that reaches the log carries it only masked
 * ([DashWifiManager.maskSsid]), as the Wi-Fi lines already did. The name used here is made up.
 */
class DashNameInLogsTest {
    private val name = "RE_AB12_345678"
    private val masked = "RE_****78"

    @Test
    fun `the session's connect line carries the name masked`() {
        val line = DashSession.connectLine(name, null)
        assertEquals("connect() — ssid='$masked' network=null", line)
    }

    @Test
    fun `the authentication that runs out points at the name masked`() {
        val reason = DashSession.authTimeoutReason(heardFromDash = true, ssid = name)
        assertTrue(reason, "Check SSID matches '$masked'" in reason)
        assertFalse(reason, name in reason)
    }

    @Test
    fun `each Wi-Fi refusal keeps its own words and carries the name masked`() {
        val words = mapOf(
            DashWifiManager.Refusal.NOT_FOUND to R.string.dash_err_ssid_not_found,
            DashWifiManager.Refusal.GONE_AFTER_JOIN to R.string.dash_err_ssid_not_found,
            DashWifiManager.Refusal.SEEN_NOT_JOINED to R.string.dash_err_auth_failed,
            DashWifiManager.Refusal.UNKNOWN to R.string.dash_err_not_found_or_password,
            DashWifiManager.Refusal.DASH_STUCK to R.string.dash_err_dash_stuck,
        )
        assertEquals(DashWifiManager.Refusal.entries.toSet(), words.keys)
        for ((refusal, text) in words) assertEquals(refusal.name, text to masked, refusal.message(name))
    }

    /** The missing-name failure goes to the log too: its example is a made-up name, not a real dash's. */
    @Test
    fun `the missing full name gives a made-up example`() {
        assertTrue(WifiDashLinkUp.FULL_NAME_NEEDED, "(ex.: $name)" in WifiDashLinkUp.FULL_NAME_NEEDED)
    }
}
