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

    @Test
    fun `the manager decides the band only for a request it really makes, sets it on Android 12 and later, and tells the choice when the request ends unanswered`() {
        val manager = File("src/main/java/com/reau/app/dash/DashWifiManager.kt").readText()
        val ask = manager.substringAfter("private fun askForTheNetwork()").substringBefore("cm.requestNetwork(request, cb, timeout)")
        val reuse = ask.indexOf("if (useAlreadyConnectedNetwork()) return")
        val decide = ask.indexOf("bandChoice.nameOn(specBuilder, band24(), requests)")
        assertTrue("after the network the phone already joined is taken", reuse in 0 until decide)
        assertEquals("the band is decided once, there", 1, Regex("""bandChoice\.(nameOn|forRequest)\(""").findAll(ask).count())
        val choice = File("src/main/java/com/reau/app/dash/BandChoice.kt").readText()
        val nameOn = choice.substringAfter("fun nameOn(").substringBefore("fun endedUnanswered()")
        assertTrue(nameOn.contains("if (!forRequest(on, Build.VERSION.SDK_INT) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return"))
        assertTrue(nameOn.contains("builder.setBand(ScanResult.WIFI_BAND_24_GHZ)"))
        assertTrue("the request's own line is as it always was", ask.contains("\", first approval\" else \"\"})\")"))
        val unavailable = manager.substringAfter("override fun onUnavailable()").substringBefore("override fun onLosing")
        assertTrue(unavailable.contains("bandChoice.endedUnanswered()"))
        assertTrue("with no switch given, a manager never names the band", manager.contains("private val band24: () -> Boolean = { false },"))
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("DashWifiManager(context, scope, pairing, band24 = { SettingsStore.open(context).load().experimentWifiBand })"))
    }
}
