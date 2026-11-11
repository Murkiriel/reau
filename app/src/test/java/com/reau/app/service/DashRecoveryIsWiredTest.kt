package com.reau.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Wi-Fi manager and the service need Android to run, which a JVM test does not have (the manager's own tests are instrumented), so the lines that
 * wire the decisions of DashRecoveryTest, StuckDashWatchTest and SendTailTest are read from the source, as [CompositorBusyIsWiredTest] does.
 */
class DashRecoveryIsWiredTest {
    private val wifi = File("src/main/java/com/reau/app/dash/DashWifiManager.kt").readText()
    private val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
    private val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()

    @Test
    fun `the watch over a loss holds a request whose Wi-Fi is being joined, as the first connection's does`() {
        assertTrue(wifi.contains("DashJoin.refileAfterLossHeld("))
        assertFalse("the plain rule would let the request go", Regex("""DashJoin\.refileAfterLoss\(""").containsMatchIn(wifi))
        assertTrue(Regex("""refileForSighting\(hold\)""").containsMatchIn(wifi))
    }

    @Test
    fun `a network found already connected is checked before and after it is taken, and again a moment later`() {
        val use = wifi.substringAfter("private fun useAlreadyConnectedNetwork()").substringBefore("private fun joinedNetworkUsable")
        assertTrue(use.split("joinedNetworkUsable(activeNetwork)").size - 1 >= 2)
        assertTrue(use.contains("verifyJoinedLater(activeNetwork)"))
        assertTrue("the watcher is registered before the second check", use.indexOf("watchJoined(activeNetwork)") < use.lastIndexOf("joinedNetworkUsable(activeNetwork)"))
    }

    @Test
    fun `a refused network is never found as already connected again`() {
        val find = wifi.substringAfter("private fun findAlreadyConnectedDashNetwork()").substringBefore("private fun readActiveWifiSsid")
        assertTrue(find.contains("isRefused(candidate)"))
    }

    @Test
    fun `an EPERM of the session reaches the Wi-Fi manager, and a hung dash reaches the rider's notice`() {
        assertTrue(live.contains("session.onBindResult = { network, refused -> up.onBindResult?.invoke(network, refused) }"))
        assertTrue(live.contains("onBindResult = wifi::bindResult"))
        assertTrue(live.contains("ProjectionNotification.showDashStuck(context)"))
        assertTrue(live.contains("ProjectionNotification.clearDashStuck(context)"))
        // That the session tells it before it reports the failure runs in DashSessionBindRefusalTest.
    }

    @Test
    fun `the failed tries of a request feed the hung dash watch, from the unavailable and from the early release`() {
        assertTrue(wifi.substringAfter("override fun onUnavailable()").substringBefore("override fun onLosing").contains("noteFailedTry()"))
        assertTrue(wifi.substringAfter("private fun refileForSighting").substringBefore("private fun newestSightingUs").contains("noteFailedTry()"))
    }

    @Test
    fun `the send tail is written when the Wi-Fi is lost and when a watchdog fires, and fed by the video loop`() {
        assertTrue(wifi.contains("DashHeard.tail?.reportOnce(\"the Wi-Fi was lost\""))
        assertTrue(session.contains("stats.sendTail.reportOnce(why, open)"))
        assertTrue(live.contains("override fun keyframeSent(bytes: Int, packets: Int) = session.keyframeSent(bytes, packets)"))
        assertTrue(File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText().contains("onKeyframe = dash::keyframeSent"))
    }

    @Test
    fun `after a refusal asked for again, the projection is told two seconds later that the Wi-Fi is up, and the network's owner is logged`() {
        val bind = wifi.substringAfter("fun bindResult(bound: Network?, refused: Boolean)").substringBefore("private fun dropWatcher")
        assertTrue(bind.contains("bindRefusals.refused(dead, phoneJoined)"))
        assertTrue(bind.contains("verdict.line(dead, owner)"))
        assertTrue("asked for again, then the news is scheduled", bind.indexOf("askForTheNetwork()") < bind.indexOf("tellTheProjectionSoon(serial)"))
        val soon = bind.substringAfter("private fun tellTheProjectionSoon")
        assertTrue(soon.contains("delay(RefusalRetry.AFTER_MS)"))
        assertTrue(soon.contains("RefusalRetry.due("))
        assertTrue(soon.contains("onNetworkAvailable?.invoke()"))
        assertTrue("a switch-off makes the news stale", wifi.substringAfter("fun disconnect()").substringBefore("private fun requestNetwork").contains("refusalSerial++"))
        assertTrue(wifi.contains("using already-connected WiFi '\${maskSsid(activeSsid)}' (network \$activeNetwork, \${networkOwner("))
        assertTrue(wifi.contains("in \${tookMs}ms (network \$network, \${networkOwner("))
    }
}
