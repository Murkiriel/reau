package com.reau.app.service

import com.reau.app.dash.DashSighting
import com.reau.app.dash.WifiConnStatus
import com.reau.app.dash.WifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan 22, item 3, on the link: a request for a network already approved that fails with the dash out of the scan starts a watch over the
 * pause after it; the dash showing up tells the projection at once (once), and nothing else does.
 */
class WifiDashLinkUpPauseWatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val wifi = MutableStateFlow(WifiState())
    private val logged = mutableListOf<String>()
    private var sighted = 0

    private class Pairing(override val joinedSsid: String?, @Volatile var dashInScan: Boolean) : WifiDashLinkUp.Pairing {
        override var ssid = "RE_AB12_345678"
        override val ssidPrefix = "RE_"
        override val password = "secret"
        override fun remember(ssid: String) { this.ssid = ssid }
        override fun seenInScan(wanted: String, isPrefix: Boolean) = dashInScan
    }

    private fun linkUp(pairing: Pairing) = WifiDashLinkUp(scope, wifi, { null }, { _, _, _ -> }, {}, pairing).also {
        it.sightingPollMs = 20
        it.pauseCutMinGapMs = 40 // the polls here are 20 ms apart: the least gap of the rule (2 s) would be a 2 s wait in every test
        it.joining = WifiDashLinkUp.Joining(log = { line -> logged += line })
    }

    private fun WifiDashLinkUp.go() = start({ _, _ -> }, {})

    private fun fail() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_AB12_345678")
        wifi.value = WifiState(WifiConnStatus.ERROR, ssid = "RE_AB12_345678", error = "unavailable")
    }

    private fun listen(): CountDownLatch {
        val news = CountDownLatch(1)
        DashSighting.listener = { sighted++; news.countDown() }
        return news
    }

    @After
    fun tearDown() {
        DashSighting.listener = null
        scope.cancel()
    }

    @Test
    fun `an approved dash out of the scan when its request failed cuts the pause when it shows up`() {
        val pairing = Pairing(joinedSsid = "RE_AB12_345678", dashInScan = false)
        val up = linkUp(pairing)
        val news = listen()
        up.go()
        fail()
        assertEquals("nothing while the dash is away", false, news.await(150, TimeUnit.MILLISECONDS))

        pairing.dashInScan = true
        assertTrue("the news came", news.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals("once", 1, sighted)
        assertTrue(logged.any { "the pause is cut" in it })
        up.stop()
    }

    @Test
    fun `a dash in the scan when its request failed is there and refusing, no watch`() {
        val pairing = Pairing(joinedSsid = "RE_AB12_345678", dashInScan = true)
        val up = linkUp(pairing)
        val news = listen()
        up.go()
        fail()
        assertEquals(false, news.await(300, TimeUnit.MILLISECONDS))
        assertEquals(0, sighted)
        up.stop()
    }

    @Test
    fun `the first approval of a network is not this watch's`() {
        val pairing = Pairing(joinedSsid = "", dashInScan = true)
        val up = linkUp(pairing)
        val news = listen()
        up.go() // seen in the scan: the request goes out, as for a first approval
        pairing.dashInScan = false // gone by the time it fails
        fail()
        Thread.sleep(100)
        pairing.dashInScan = true
        assertEquals(false, news.await(300, TimeUnit.MILLISECONDS))
        up.stop()
    }

    @Test
    fun `the next start ends the watch, and so does stop`() {
        val pairing = Pairing(joinedSsid = "RE_AB12_345678", dashInScan = false)
        val up = linkUp(pairing)
        val news = listen()
        up.go()
        fail()
        up.go() // the pause is over: a new request
        pairing.dashInScan = true
        assertEquals("the watch of the pause that ended says nothing", false, news.await(300, TimeUnit.MILLISECONDS))

        val other = Pairing(joinedSsid = "RE_AB12_345678", dashInScan = false)
        val second = linkUp(other)
        second.go()
        fail()
        second.stop()
        other.dashInScan = true
        assertEquals(false, news.await(300, TimeUnit.MILLISECONDS))
        up.stop()
    }

    @Test
    fun `a request that is answered starts no watch`() {
        val pairing = Pairing(joinedSsid = "RE_AB12_345678", dashInScan = false)
        val up = linkUp(pairing)
        val news = listen()
        up.go()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_AB12_345678")
        pairing.dashInScan = true
        assertEquals(false, news.await(300, TimeUnit.MILLISECONDS))
        up.stop()
    }
}
