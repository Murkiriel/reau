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
        assertTrue(wifi.after("private fun readScan()").contains("ScanHistory.trail.note("))
        assertTrue(wifi.between("private fun searchDashSsid", "catch (e: Exception)").contains("ScanHistory.trail.note("))
        val state = wifi.between("private fun emptyListState()", "/** Log only: whether the phone is joined")
        assertTrue(state.contains("lastNonEmpty = ScanHistory.trail.describe("))
        assertTrue(state.contains("wifiJoined = joinedWifi()"))
    }

    @Test
    fun `both lines of the request and the end of it read the list through the one function that keeps the trail`() {
        assertTrue(wifi.between("private fun lastScanEvidence()", "/** Log only, read when").contains("readScan()"))
        assertEquals("the list is read from the system in two places only: the lookup and readScan", 2, Regex("wifi\\.scanResults\\.mapNotNull \\{ r ->").findAll(wifi).count())
    }

    @Test
    fun `each look of a watch passes through the look log, which writes only when the list moved`() {
        val look = wifi.between("fun seenInLastScan(", "/**")
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
