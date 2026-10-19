package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone must be without a network for a few seconds before the dash is told the signal is lost. */
class NoNetworkWatchTest {
    private var now = 0L
    private var online = true
    private val watch = NoNetworkWatch(clockMs = { now }, online = { online }, afterMs = 5_000L)

    @Test
    fun `with a network it is never without`() {
        now = 100_000; assertEquals(false, watch.without())
    }

    @Test
    fun `a gap shorter than the wait is not yet without a network`() {
        online = false
        now = 10_000; assertEquals(false, watch.without())
        now = 14_999; assertEquals(false, watch.without())
    }

    @Test
    fun `after the wait it is without a network, until the network is back`() {
        online = false
        now = 10_000; watch.without()
        now = 15_000; assertEquals(true, watch.without())
        online = true
        now = 16_000; assertEquals(false, watch.without())
    }

    @Test
    fun `a blip in the gap restarts the wait`() {
        online = false
        now = 10_000; watch.without()
        online = true
        now = 12_000; watch.without()
        online = false
        now = 13_000; watch.without()
        now = 17_999; assertEquals(false, watch.without())
        now = 18_000; assertEquals(true, watch.without())
    }
}
