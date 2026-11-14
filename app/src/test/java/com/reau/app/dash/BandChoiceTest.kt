package com.reau.app.dash

import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The experimental band of the request for the dash's Wi-Fi. Off (the default) no request ever names a band; on, a request names 2.4 GHz
 * on Android 12 and later, and a request with the band that ends unanswered is followed by one without it.
 */
class BandChoiceTest {
    private val choice = BandChoice()

    @Test
    fun `the switch is off by default, and off no request names the band, whatever happened before`() {
        assertFalse(REauSettings().experimentWifiBand)
        for (sdk in listOf(29, 30, 31, 34, 36)) assertFalse("sdk $sdk", choice.forRequest(on = false, sdk = sdk))
        choice.unanswered()
        assertFalse(choice.forRequest(on = false, sdk = 34))
        assertFalse(choice.lastNamedTheBand)
    }

    @Test
    fun `on, the band is named from Android 12 and not before`() {
        assertFalse(BandChoice().forRequest(on = true, sdk = 29))
        assertFalse(BandChoice().forRequest(on = true, sdk = 30))
        assertTrue(BandChoice().forRequest(on = true, sdk = 31))
        assertTrue(BandChoice().forRequest(on = true, sdk = 34))
    }

    @Test
    fun `requests that are answered keep naming the band`() {
        repeat(5) { assertTrue(choice.forRequest(on = true, sdk = 34)) }
    }

    @Test
    fun `a request with the band that ends unanswered is followed by one without it, and then the band comes back`() {
        assertTrue(choice.forRequest(on = true, sdk = 34))
        assertTrue(choice.lastNamedTheBand)
        choice.unanswered()
        assertFalse("the next goes without the band", choice.forRequest(on = true, sdk = 34))
        assertFalse(choice.lastNamedTheBand)
        choice.unanswered()
        assertTrue("an unanswered request without the band does not hold the band back", choice.forRequest(on = true, sdk = 34))
        choice.unanswered()
        assertFalse(choice.forRequest(on = true, sdk = 34))
        assertTrue(choice.forRequest(on = true, sdk = 34))
    }

    @Test
    fun `the end of a request is told once`() {
        assertTrue(choice.forRequest(on = true, sdk = 34))
        choice.unanswered()
        assertFalse("said already", choice.lastNamedTheBand)
    }

    @Test
    fun `the lines of the ride log`() {
        assertEquals("request #7 names the 2.4 GHz band (experiment)", BandChoice.requestLine(7))
        assertTrue(BandChoice.UNANSWERED_LINE.endsWith("the next one is made without the band"))
    }

    /** What [BandChoice.nameOn] does for a request: whether it names the band, and the line it writes to the ride log (the tests run on a stand-in for Android 12's request). */
    private fun nameOn(choice: BandChoice, on: Boolean, sdk: Int, request: Int): Pair<Int, List<String>> {
        val logFile = java.nio.file.Files.createTempFile("band-choice", ".log").toFile()
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        try {
            var named = 0
            choice.nameOn(on, sdk, request) { named++ }
            com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
            return named to logFile.readLines().filter { "request #" in it }
        } finally {
            com.reau.app.data.RideDiagnostics.testOnlyReset()
            logFile.delete()
        }
    }

    @Test
    fun `a request that names the band says so in the log, and one that does not writes nothing`() {
        val (named, lines) = nameOn(choice, on = true, sdk = 34, request = 7)
        assertEquals("the band is named once", 1, named)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].endsWith(BandChoice.requestLine(7)))
        assertEquals(0 to emptyList<String>(), nameOn(BandChoice(), on = false, sdk = 34, request = 8))
        assertEquals("Android 11 does not take the band", 0 to emptyList<String>(), nameOn(BandChoice(), on = true, sdk = 30, request = 9))
    }

    @Test
    fun `the request after one that ended unanswered does not name the band, and the one after that does again`() {
        assertEquals(1, nameOn(choice, on = true, sdk = 34, request = 1).first)
        choice.endedUnanswered()
        assertEquals(0 to emptyList<String>(), nameOn(choice, on = true, sdk = 34, request = 2))
        assertEquals(1, nameOn(choice, on = true, sdk = 34, request = 3).first)
    }

    @Test
    fun `the manager decides the band only for a request it really makes, sets it on Android 12 and later, and tells the choice when the request ends unanswered`() {
        val manager = File("src/main/java/com/reau/app/dash/DashWifiManager.kt").readText()
        val ask = manager.between("private fun askForTheNetwork()", "cm.requestNetwork(request, cb, timeout)")
        val reuse = ask.indexOf("if (useAlreadyConnectedNetwork()) return")
        val decide = ask.indexOf("bandChoice.nameOn(specBuilder, band24(), requests)")
        assertTrue("after the network the phone already joined is taken", reuse in 0 until decide)
        assertEquals("the band is decided once, there", 1, Regex("""bandChoice\.(nameOn|forRequest)\(""").findAll(ask).count())
        assertTrue("the request's own line is as it always was", ask.contains("\", first approval\" else \"\"})\")"))
        assertTrue("the band is named on the request's builder", File("src/main/java/com/reau/app/dash/BandChoice.kt").readText().contains("builder.setBand(ScanResult.WIFI_BAND_24_GHZ)"))
        val unavailable = manager.between("override fun onUnavailable()", "override fun onLosing")
        assertTrue(unavailable.contains("bandChoice.endedUnanswered()"))
        assertTrue("with no switch given, a manager never names the band", manager.contains("private val band24: () -> Boolean = { false },"))
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("DashWifiManager(context, scope, pairing, band24 = { SettingsStore.open(context).load().experimentWifiBand })"))
    }

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
}
