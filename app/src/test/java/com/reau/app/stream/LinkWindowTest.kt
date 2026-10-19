package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each 5 s line of the stream says how fast the bike went (from Android Auto's route,
 * [NavSpeed]) and how the bike's Wi-Fi was, so a ride at 40, 80 and 100 km/h shows where the dash falls behind.
 */
class LinkWindowTest {

    @Test
    fun `the speed is the mean with its top, the Wi-Fi its worst signal and its worst link`() {
        val w = LinkWindow()
        w.add(LinkSample(speedKmh = 78.4, wifiDbm = -58, wifiMbps = 72))
        w.add(LinkSample(speedKmh = 101.6, wifiDbm = -66, wifiMbps = 39))
        w.add(LinkSample(speedKmh = null, wifiDbm = -61, wifiMbps = 26))
        assertEquals("speed~90km/h(max 102) wifi=-66dBm/26Mbps(worst)", w.text())
    }

    @Test
    fun `nothing known says so`() {
        val w = LinkWindow()
        w.add(null)
        w.add(LinkSample(speedKmh = null, wifiDbm = null, wifiMbps = null))
        assertEquals("speed=? wifi=?", w.text())
        assertEquals("speed=? wifi=?", LinkWindow().text())
    }

    @Test
    fun `a link speed Android does not know is left out`() {
        val w = LinkWindow()
        w.add(LinkSample(speedKmh = 40.0, wifiDbm = -50, wifiMbps = -1))
        assertEquals("speed~40km/h(max 40) wifi=-50dBm/?Mbps(worst)", w.text())
    }
}
