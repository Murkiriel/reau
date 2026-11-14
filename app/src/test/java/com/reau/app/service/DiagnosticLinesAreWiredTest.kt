package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ride log lines of 08/11/2026 (item 15a: the empty list's facts, the age of the scan at each look, the net's class and p, the time without
 * image) are written from classes that need Android (the Wi-Fi manager, the compositor, the session), so the lines that wire them are read from
 * the source. What each line says is covered by [com.reau.app.dash.ScanLooksTest], [com.reau.app.video.LookAuditTest] and
 * [com.reau.app.dash.ImageGapTest]. None of it changes what the app does.
 */
class DiagnosticLinesAreWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val wifi = source("dash/DashWifiManager.kt")
    // The manager's reading of the Wi-Fi list, in a file of its own since 14/11/2026.
    private val scan = source("dash/DashScan.kt")
    private val session = source("dash/DashSession.kt")
    private val compositor = source("video/DashCompositor.kt")

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
    fun `every reading of the list that has networks goes into the trail, and the empty one is described with it`() {
        assertTrue(scan.between("fun read()", "/** The newest stamp of the dash").contains("ScanHistory.trail.note("))
        assertTrue(scan.between("private fun search(", "catch (e: Exception)").contains("ScanHistory.trail.note("))
        val state = scan.between("private fun emptyListState(", "/** Log only: whether the phone is joined")
        assertTrue(state.contains("lastNonEmpty = ScanHistory.trail.describe("))
        assertTrue(state.contains("wifiJoined = joinedWifi(network)"))
        assertTrue("the manager's lookup is the scan's", wifi.contains("fun findDashSsid(prefix: String): String? = scan.find(prefix)"))
    }

    @Test
    fun `both lines of the request and the end of it read the list through the one function that keeps the trail`() {
        assertTrue(scan.between("fun evidence(", "/** Log only, read when").contains("scanEvidence(read(), ssid, prefix,"))
        assertTrue(wifi.between("private fun lastScanEvidence()", "/**").contains("scan.evidence(pendingSsid, pendingPrefix, network)"))
        val reading = Regex("wifi\\.scanResults\\.mapNotNull \\{ r ->")
        assertEquals("the list is read from the system in two places only: the lookup and read", 2, reading.findAll(scan).count())
        assertEquals("and never by the manager itself", 0, Regex("scanResults").findAll(wifi).count())
        assertTrue("the failed try reads it through the scan too", wifi.between("private fun noteFailedTry()", "DashStuck.sighting(").contains("scan.read()"))
    }

    @Test
    fun `each look of a watch passes through the look log, which writes only when the list moved`() {
        assertTrue(wifi.contains("fun seenInLastScan(ssid: String, prefix: Boolean): Boolean = scan.seenRecently(ssid, prefix)"))
        val look = scan.between("fun seenRecently(", "/**")
        assertTrue(look.contains("scanLooks.line(seen, ssid, prefix, nowUs)"))
        assertTrue(look.contains("RideDiagnostics.log(\"wifi\", it)"))
        assertTrue(look.indexOf("scanLooks.line") < look.indexOf("DashJoin.seenRecently"))
    }

    @Test
    fun `the net's class and p are written only in the debug build and never before the listener is told`() {
        assertTrue(compositor.contains("var auditLooks: Boolean = com.reau.app.BuildConfig.DEBUG"))
        val take = compositor.substringAfter("private fun takeLook()")
        assertTrue(take.contains("PictureLook.of(it, PictureLook.SAMPLE_W, PictureLook.SAMPLE_H, lookResult)"))
        assertTrue(take.contains("if (kind != null && auditLooks) lookAudit.line(kind, lookResult.confidence, now)?.let(log)"))
        assertTrue(take.indexOf("lookAudit.line") < take.indexOf("listener(kind ?: return)"))
    }

    @Test
    fun `the image gap opens at the Wi-Fi loss and at the session failure, learns the dash's age, and closes at the stream`() {
        assertTrue(wifi.between("private fun bikeLost()", "stuck.lost(").contains("ImageGap.clock.lost(now, DashHeard.agoMs(now))"))
        assertTrue(session.substringAfter("internal fun fail(").substringBefore("logTrail(").contains("ImageGap.clock.lost("))
        assertTrue(session.substringAfter("private fun noteHeartbeat(").substringBefore("}").contains("ImageGap.clock.dashAge(firstHeartbeatAtMs, counter)"))
        assertTrue(session.substringAfter("fun startStreaming()").substringBefore("launchProjectionHeartbeat()").contains("ImageGap.clock.streaming("))
        assertTrue(source("service/LiveSides.kt").contains("\"image gap\" to { com.reau.app.dash.ImageGap.clock.forget() }"))
    }

    @Test
    fun `the auth timeout reads the signal and the packets of the session, counted from the session's start`() {
        val wait = session.substringAfter("if (!authConfirmed) {").substringBefore("} else {")
        assertTrue(wait.contains("rssiDbm = linkProbe(linkNetwork)?.rssiDbm, packets = rxPackets"))
        assertTrue(session.substringAfter("internal fun receivePacket(").substringBefore("try {").contains("rxPackets++"))
        assertTrue(session.contains("rxPackets = 0"))
    }
}
