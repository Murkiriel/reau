package com.reau.app.dash

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLog
import com.reau.app.data.RideDiagnostics
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REau's own logcat, which the app copies to a
 * file on the phone, carries no packet bytes (the song and the caller of the dash's cards once went there in hex,
 * which must not happen) and no second copy of Android Auto's lines already in the ride log.
 */
@RunWith(AndroidJUnit4::class)
class PrivateLogcatTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null
    private val nonce = System.nanoTime().toString()

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
        AaLog.sink = null
    }

    /** This process's logcat lines after [marker] (logged by the test itself). */
    private fun ownLogcatAfter(marker: String): List<String> {
        val pid = android.os.Process.myPid()
        val out = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "--pid=$pid")).inputStream.bufferedReader().readLines()
        val from = out.indexOfLast { marker in it }
        assertTrue("the marker is in this process's logcat", from >= 0)
        return out.drop(from + 1)
    }

    @Test
    fun theDashsPacketsDoNotGoToLogcatNorTheCardsText() {
        val marker = "private-logcat-start-$nonce"
        Log.i("PrivateLogcatTest", marker)
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) })
            .also { session = it }
        s.connect("DashFake")
        runBlocking {
            val deadline = System.currentTimeMillis() + 8_000
            while (s.state.value != DashState.READY && System.currentTimeMillis() < deadline) delay(50)
        }
        assertEquals(DashState.READY, s.state.value)
        s.startStreaming()
        fake.ackIdrFrame()
        s.updateNowPlaying("Song$nonce", "Album", "Band")
        s.showCaller("Ana$nonce")
        fake.pressButton(0x14)
        runBlocking { delay(2_500) }
        assertTrue("the cards went out", fake.countOf(0x05, 0x22) >= 1 && fake.countOf(0x05, 0x0D) >= 1)

        val lines = ownLogcatAfter(marker)
        val hexOfName = "Ana$nonce".toByteArray().joinToString(" ") { "%02X".format(it) }.take(20)
        val leaks = lines.filter { "TX →" in it || "RX ←" in it || "RX RAW" in it || "full=" in it || "DASH TELEMETRY" in it ||
            "Song$nonce" in it || "Ana$nonce" in it || hexOfName in it }
        assertTrue("no packet bytes nor card text in logcat: ${leaks.take(3)}", leaks.isEmpty())
    }

    /** The dash's Wi-Fi name (made up here) goes masked to logcat and to the ride log. */
    @Test
    fun theDashsNameGoesMaskedToLogcatAndTheRideLog() {
        val marker = "private-logcat-name-$nonce"
        Log.i("PrivateLogcatTest", marker)
        RideDiagnostics.startProcess("test")
        RideDiagnostics.logAndWait("test", marker)
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) })
            .also { session = it }
        s.connect("RE_AB12_345678")
        RideDiagnostics.logAndWait("test", "end of $marker")

        val diag = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "diag")
        val ride = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() }?.readText().orEmpty()
            .substringAfter(marker, "")
        assertTrue("the ride log has the connect line, masked: $ride", "connect() — ssid='RE_****78'" in ride)
        assertTrue("the ride log has no full name", "RE_AB12_345678" !in ride)
        val lines = ownLogcatAfter(marker)
        assertTrue("logcat has the connect line, masked: ${lines.takeLast(5)}", lines.any { "ssid='RE_****78'" in it })
        assertTrue("logcat has no full name: ${lines.filter { "RE_AB12" in it }}", lines.none { "RE_AB12_345678" in it })
    }

    @Test
    fun androidAutosLinesGoToTheRideLogAndOnlyWarningsAndErrorsAlsoToLogcat() {
        val marker = "private-logcat-aa-$nonce"
        Log.i("PrivateLogcatTest", marker)
        val sunk = CopyOnWriteArrayList<String>()
        AaLog.sink = { sunk += it }
        AaLog.i("info-$nonce")
        AaLog.w("warn-$nonce")
        AaLog.e("error-$nonce")
        Thread.sleep(300)
        val lines = ownLogcatAfter(marker)
        assertEquals(listOf("[AA] info-$nonce", "[AA] W: warn-$nonce", "[AA] E: error-$nonce"), sunk.toList())
        assertTrue("an info line is not copied to logcat when it goes to the ride log", lines.none { "info-$nonce" in it })
        assertTrue("a warning still goes to logcat", lines.any { "warn-$nonce" in it })
        assertTrue("an error still goes to logcat", lines.any { "error-$nonce" in it })

        AaLog.sink = null
        AaLog.i("nosink-$nonce")
        Thread.sleep(300)
        assertTrue("with no sink (tests), logcat has it", ownLogcatAfter(marker).any { "nosink-$nonce" in it })
    }
}
