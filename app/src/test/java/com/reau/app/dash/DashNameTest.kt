package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a dash's Wi-Fi name, and learning it while the app is
 * on screen: Android only lets the app read the Wi-Fi scan then (location "while in use"), so the name is learned when
 * the rider opens REau near the bike and is already there when a projection starts in the background.
 */
class DashNameTest {
    @Test
    fun `a dash name is longer than the factory prefix alone`() {
        assertFalse(DashName.isDashName("RE_"))
        assertFalse(DashName.isDashName(""))
        assertTrue(DashName.isDashName("RE_AB12_345678"))
    }

    @Test
    fun `an answer for a prefix must start with it and be a dash name`() {
        assertTrue(DashName.isFullName("RE_AB12_345678", prefix = "RE_"))
        assertTrue("the whole name typed as the prefix", DashName.isFullName("RE_AB12_345678", prefix = "RE_AB12_345678"))
        assertFalse(DashName.isFullName("RE_", prefix = "RE_"))
        assertFalse(DashName.isFullName("XRE_DASH", prefix = "RE_"))
    }

    private class Memory(var ssid: String = "", val prefix: String = "RE_", val scan: (String) -> String? = { null }) {
        var scans = 0
        fun learner() = DashName.Learner(
            known = { ssid },
            prefix = { prefix },
            scan = { p -> scans++; scan(p) },
            remember = { ssid = it },
        )
    }

    @Test
    fun `opening the app near the bike learns the dash's name from the last scan`() {
        val m = Memory(scan = { if (it == "RE_") "RE_AB12_345678" else null })
        assertEquals("RE_AB12_345678", m.learner().learn())
        assertEquals("RE_AB12_345678", m.ssid)
    }

    @Test
    fun `a dash already known is not looked for again`() {
        val m = Memory(ssid = "RE_AB12_345678", scan = { "RE_OTHER_000001" })
        assertNull(m.learner().learn())
        assertEquals(0, m.scans)
        assertEquals("RE_AB12_345678", m.ssid)
    }

    @Test
    fun `nothing useful in the scan learns nothing`() {
        for (answer in listOf(null, "RE_", "XRE_DASH")) {
            val m = Memory(scan = { answer })
            assertNull("scan said $answer", m.learner().learn())
            assertEquals("scan said $answer", "", m.ssid)
        }
    }

    @Test
    fun `the network request asks Android for the connected name where it can`() {
        assertEquals(0, DashName.callbackFlags(sdk = 30))
        assertEquals(DashName.FLAG_INCLUDE_LOCATION_INFO, DashName.callbackFlags(sdk = 31))
        assertEquals(DashName.FLAG_INCLUDE_LOCATION_INFO, DashName.callbackFlags(sdk = 36))
    }
}
