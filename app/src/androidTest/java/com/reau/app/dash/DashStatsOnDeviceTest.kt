package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.data.RideDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the emulator: a real [DashSession] against
 * [FakeDashPeer] over loopback UDP counts what the two exchanged, and the counts are what the fake really sent and
 * received; the dash-stats line goes to the ride log every period and once at the end.
 */
@RunWith(AndroidJUnit4::class)
class DashStatsOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fake = FakeDashPeer()
    private val session = DashSession(
        scope,
        socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) },
        statsEveryMs = 300,
    )
    private val diag = File(context.filesDir, "diag")
    private val marker = "dash-stats-test-${System.nanoTime()}"

    @After
    fun tearDown() {
        session.disconnect()
        scope.cancel()
        fake.close()
    }

    private fun await(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(what, condition())
    }

    private fun logSinceMarker(): String {
        val newest = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() } ?: return ""
        return newest.readText().substringAfterLast(marker, "")
    }

    @Test
    fun theCountsAreWhatTheFakeDashExchangedAndTheLineGoesToTheLog() {
        RideDiagnostics.startProcess("test")
        RideDiagnostics.logAndWait("test", marker)
        session.connect("DashFake")
        await("ready") { session.state.value == DashState.READY }

        repeat(2) { fake.pressButton(0x09) }
        fake.pressButton(0x0A)
        repeat(3) { fake.ackIdrFrame() }
        repeat(5) { fake.ackPFrame() }
        await("the buttons and acks counted") { session.stats.totals().buttons == 3L && session.stats.totals().acks == 8L }
        await("a periodic dash-stats line with them") { logSinceMarker().contains("total tx=") && logSinceMarker().contains(" buttons=3 ") }
        val log = logSinceMarker()
        assertTrue("the first of each button code: $log", "dash-stats first button 0x09" in log && "dash-stats first button 0x0A" in log)
        assertEquals("told once each", 1, Regex("first button 0x09").findAll(log).count())
        assertTrue("heartbeats counted: $log", Regex("heartbeats=[1-9]").containsMatchIn(log))

        session.disconnect()
        Thread.sleep(500) // the last packets on their way
        val t = session.stats.totals()
        assertEquals("every packet the fake received was counted as sent", fake.packetsReceived.get().toLong(), t.tx)
        assertEquals("every TLV the fake sent was counted as received", fake.tlvsSent.get().toLong(), t.rx)
        await("and the line of the end") { logSinceMarker().contains("(session end)") }
    }
}
