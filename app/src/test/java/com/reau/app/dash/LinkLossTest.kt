package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkLossTest {
    private val known = NetFacts(upAtMs = 1_000, mhz = 2447, rssiDbm = -42, specifier = true, validated = false, notRestricted = true)

    @Test
    fun `the real channel pairs of the gaucho log say another channel`() {
        // 2467→2462, 2462→2457, 2457→2447, 2447→2427 : four drops in six minutes, each on a new channel
        for ((before, now) in listOf(2467 to 2462, 2462 to 2457, 2457 to 2447, 2447 to 2427)) {
            assertEquals(LinkLoss.Channel.OTHER, LinkLoss.channel(before, now))
            assertEquals(
                "the dash's Wi-Fi is back on another channel ($before → $now MHz): its access point restarted",
                LinkLoss.backLine(before, now),
            )
        }
    }

    @Test
    fun `the same channel is said so and the first connection says nothing`() {
        assertEquals(LinkLoss.Channel.SAME, LinkLoss.channel(2427, 2427))
        assertEquals("the dash's Wi-Fi is back on the same channel (2427 MHz)", LinkLoss.backLine(2427, 2427))
        assertEquals(LinkLoss.Channel.FIRST, LinkLoss.channel(null, 2427))
        assertNull(LinkLoss.backLine(null, 2427))
    }

    @Test
    fun `a loss with every fact known writes them in one line`() {
        assertEquals(
            "link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -42, mechanism=specifier, validated=false, " +
                "restricted=false, last dash packet 0.2 s before, screen=on)",
            LinkLoss.lostLine(known, nowMs = 60_000, packetAgoMs = 200, screenOn = true, reconnectInMs = 4_000),
        )
    }

    @Test
    fun `what is not known says unknown, not a number`() {
        val line = LinkLoss.lostLine(null, nowMs = 60_000, packetAgoMs = null, screenOn = null, reconnectInMs = 4_000)
        assertEquals(
            "link lost — reconnecting in 4000ms (after an unknown time on an unknown channel; rssi unknown, mechanism=unknown, " +
                "validated=unknown, restricted=unknown, last dash packet unknown, screen=unknown)",
            line,
        )
        val partial = LinkLoss.lostLine(NetFacts(upAtMs = 0, mhz = 2427), 5_000, 3_500, false, 4_000)
        assertTrue(partial, partial.contains("after 5 s on 2427 MHz; rssi unknown"))
        assertTrue(partial, partial.contains("last dash packet 3.5 s before, screen=off"))
    }

    @Test
    fun `the line keeps the old beginning the log reader looks for and never holds a network name`() {
        val line = LinkLoss.lostLine(known, 60_000, 200, true, 4_000)
        assertTrue(line.startsWith("link lost — reconnecting in 4000ms"))
        assertFalse(line.contains("RE_"))
    }

    @Test
    fun `the dash is heard, forgotten and never heard`() {
        DashHeard.forget()
        assertNull(DashHeard.agoMs(5_000))
        DashHeard.heard(4_800)
        assertEquals(200L, DashHeard.agoMs(5_000))
        DashHeard.forget()
        assertNull(DashHeard.agoMs(5_000))
    }

    @Test
    fun `the warning that a network is going names the time left`() {
        assertEquals("link losing: Android will drop the dash's Wi-Fi within 3000 ms", LinkLoss.losingLine(3_000))
    }

    @Test
    fun `the phone's own side of a loss is one line with the radio, the main Wi-Fi and the networks up, and no name`() {
        val line = LinkLoss.phoneSideLine(
            "enabled", "DISCONNECTED",
            listOf(LinkLoss.PhoneNet("cell", validated = true), LinkLoss.PhoneNet("wifi", validated = false)),
            LinkLoss.PhoneNet("cell", validated = true),
        )
        assertEquals(
            "phone side of the loss: Wi-Fi radio enabled, main Wi-Fi DISCONNECTED, networks up: cell(validated) + wifi, default cell(validated)",
            line,
        )
        assertFalse(line.contains("RE_"))
        assertFalse("not the beginning the log reader takes for a drop of the Wi-Fi", line.startsWith("link lost"))
    }

    @Test
    fun `the phone's side says unknown for what Android would not say, and none for no network`() {
        assertEquals(
            "phone side of the loss: Wi-Fi radio unknown, main Wi-Fi unknown, networks up: none, default none",
            LinkLoss.phoneSideLine(null, null, emptyList(), null),
        )
    }

    @Test
    fun `the Wi-Fi radio's state has a word for each state`() {
        assertEquals("enabled", PhoneSide.radioName(android.net.wifi.WifiManager.WIFI_STATE_ENABLED))
        assertEquals("disabled", PhoneSide.radioName(android.net.wifi.WifiManager.WIFI_STATE_DISABLED))
        assertEquals("enabling", PhoneSide.radioName(android.net.wifi.WifiManager.WIFI_STATE_ENABLING))
        assertEquals("disabling", PhoneSide.radioName(android.net.wifi.WifiManager.WIFI_STATE_DISABLING))
        assertEquals("unknown", PhoneSide.radioName(android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN))
    }
}
