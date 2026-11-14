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
    // What the manager reads of a Wi-Fi the phone joined by itself, in a file of its own since 14/11/2026.
    private val joined = File("src/main/java/com/reau/app/dash/JoinedWifi.kt").readText()
    private val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
    private val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()

    /**
     * The text between two anchors of a source. `substringAfter` and `substringBefore` hand back the whole text when the anchor is not there, so a
     * function that moved to another file would leave the check looking at more than it should, and passing: each anchor has to be there.
     */
    private fun String.between(start: String, end: String): String {
        val rest = after(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    private fun String.after(start: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        return substringAfter(start)
    }

    @Test
    fun `the watch over a loss holds a request whose Wi-Fi is being joined, as the first connection's does`() {
        assertTrue(wifi.contains("DashJoin.refileAfterLossHeld("))
        assertFalse("the plain rule would let the request go", Regex("""DashJoin\.refileAfterLoss\(""").containsMatchIn(wifi))
        assertTrue(Regex("""refileForSighting\(hold\)""").containsMatchIn(wifi))
    }

    @Test
    fun `a network found already connected is checked before and after it is taken, and again a moment later`() {
        val use = wifi.between("private fun useAlreadyConnectedNetwork()", "private fun verifyJoinedLater")
        assertTrue(use.split("joinedWifi.usable(activeNetwork)").size - 1 >= 2)
        assertTrue(use.contains("verifyJoinedLater(activeNetwork)"))
        assertTrue("the watcher is registered before the second check", use.indexOf("watchJoined(activeNetwork)") < use.lastIndexOf("joinedWifi.usable(activeNetwork)"))
        assertTrue(wifi.between("private fun verifyJoinedLater", "private fun askForTheNetwork()").contains("!joinedWifi.usable(joined)"))
        assertTrue(joined.between("fun usable(joined: Network)", "fun ssidOf(").contains("JoinedNetwork.usable(caps != null,"))
    }

    @Test
    fun `a refused network is never found as already connected again`() {
        val find = joined.between("fun find(", "fun activeSsid")
        assertTrue(find.contains("if (isRefused(candidate)) return@firstOrNull false"))
        assertTrue(
            "the manager hands its refused networks to the search",
            wifi.contains("private fun findAlreadyConnectedDashNetwork(): Pair<Network, String>? = joinedWifi.find(pendingSsid, pendingPrefix, refusedNetworks::has)"),
        )
        assertTrue(wifi.between("fun bindResult(bound: Network?, refused: Boolean)", "release()").contains("dead?.let(refusedNetworks::remember)"))
    }

    @Test
    fun `what was known of the network is closed at its loss and forgotten by a fresh connect, where the manager's own fields were`() {
        val lost = wifi.between("private fun bikeLost()", "private fun watchAfterLoss()")
        assertTrue("the line of the loss is written with the facts, before they are let go", lost.indexOf("LinkLoss.lostLine(notes.facts,") in 0 until lost.indexOf("notes.lost()"))
        assertTrue(lost.indexOf("DashHeard.tail?.reportOnce(") in 0 until lost.indexOf("notes.lost()"))
        assertTrue("and before the network is forgotten", lost.indexOf("notes.lost()") < lost.indexOf("network = null"))
        assertTrue(wifi.between("fun connect(ssid: String", "requestNetwork()").contains("notes.forget()"))
        assertTrue(wifi.between("private fun noteNetwork(", "private fun capsRead(").contains("notes.note(caps?.let(::capsRead), MonotonicClock.nowMs())?.let { com.reau.app.data.RideDiagnostics.log(\"wifi\", it) }"))
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
        assertTrue(wifi.between("override fun onUnavailable()", "override fun onLosing").contains("noteFailedTry()"))
        assertTrue(wifi.between("private fun refileForSighting", "// ── A hung dash, and a network Android refuses").contains("noteFailedTry()"))
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
        val bind = wifi.between("fun bindResult(bound: Network?, refused: Boolean)", "private fun dropWatcher")
        assertTrue(bind.contains("bindRefusals.refused(dead, phoneJoined)"))
        assertTrue(bind.contains("verdict.line(dead, owner)"))
        assertTrue("asked for again, then the news is scheduled", bind.indexOf("askForTheNetwork()") < bind.indexOf("tellTheProjectionSoon(serial)"))
        val soon = bind.after("private fun tellTheProjectionSoon")
        assertTrue(soon.contains("delay(RefusalRetry.AFTER_MS)"))
        assertTrue(soon.contains("RefusalRetry.due("))
        assertTrue(soon.contains("onNetworkAvailable?.invoke()"))
        assertTrue("a switch-off makes the news stale", wifi.between("fun disconnect()", "private fun requestNetwork").contains("refusalSerial++"))
        assertTrue(wifi.contains("using already-connected WiFi '\${maskSsid(activeSsid)}' (network \$activeNetwork, \${networkOwner("))
        assertTrue(wifi.contains("in \${tookMs}ms (network \$network, \${networkOwner("))
    }
}
