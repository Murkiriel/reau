package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.data.DetailedLog
import com.reau.app.data.RideDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the emulator: a dash session against [FakeDashPeer]
 * writes no detail line with the switch off, and with it on every packet goes in byte by byte, under the ceiling of
 * lines a second, with the route card's text left out.
 */
@RunWith(AndroidJUnit4::class)
class DetailedLogOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fake = FakeDashPeer()
    private val session = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) })
    private val diag = File(context.filesDir, "diag")
    private val marker = "detailed-log-test-${System.nanoTime()}"
    private val before = DetailedLog.enabled

    @After
    fun tearDown() {
        session.disconnect()
        scope.cancel()
        fake.close()
        DetailedLog.enabled = before
    }

    private fun await(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(what, condition())
    }

    private fun logSinceMarker(): String {
        val newest = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() } ?: return ""
        return newest.readText().substringAfter(marker, "")
    }

    private fun sessionWithAcks(acks: Int) {
        RideDiagnostics.startProcess("test")
        RideDiagnostics.logAndWait("test", marker)
        session.connect("DashFake")
        await("ready") { session.state.value == DashState.READY }
        repeat(acks) { fake.ackPFrame() }
        await("the acks counted") { session.stats.totals().acks == acks.toLong() }
        RideDiagnostics.logAndWait("test", "end of $marker")
    }

    @Test
    fun switchedOffASessionWritesNoDetailLine() {
        DetailedLog.enabled = false
        sessionWithAcks(acks = 5)
        val log = logSinceMarker()
        assertTrue("no [detail] line: $log", "[detail]" !in log)
    }

    @Test
    fun switchedOnThePacketsGoInByteByByteUnderTheCeiling() {
        DetailedLog.enabled = true
        sessionWithAcks(acks = 200)
        await("the dropped lines said when the next second starts", timeoutMs = 5_000) {
            RideDiagnostics.logAndWait("test", "tick")
            "lines over the ceiling of 50 a second dropped" in logSinceMarker()
        }
        val log = logSinceMarker()
        assertTrue("the phone's packets: $log", Regex("""\[detail] dash tx \p{XDigit}{4}:""").containsMatchIn(log))
        assertTrue("the dash's acks, byte by byte", "[detail] dash rx 0904:55" in log)
        assertTrue("the key exchange left out", Regex("""0800:<\d+B left out>""").containsMatchIn(log))
        val perSecond = log.lines().filter { "[detail]" in it && "over the ceiling" !in it }.groupBy { it.take(8) }.values.maxOf { it.size }
        assertTrue("never more than 50 a second, got $perSecond", perSecond <= 50)
    }
}
