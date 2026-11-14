package com.reau.app.settings

import com.reau.app.aa.Messages
import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seven experimental switches of the last round (weather age, battery heat ceiling, GPS speed, recalculation on a short "inactive", colour
 * matrix, protocol 6.0, tolerant dash watch): off by default, kept by [REauSettings.sanitized], stored each on its own, said in the settings line of
 * the ride log and in `connection experiments ON:`, known by the start guard. What each does is its own test's (HeatCeilingTest, ProfileSpeedTest, RecalcHoldTest,
 * ColorMatrixTest, ProtocolAskTest, RxQuietTest, WeatherKeeperTest).
 */
class PanelAndLinkExperimentsTest {
    private val fields = listOf(
        "experimentWeatherAge", "experimentHeatCeiling", "experimentGpsSpeed", "experimentRecalcInactive", "experimentColorMatrix",
        "experimentProtocolSix", "experimentTolerantRx",
    )
    private val readers = listOf<(REauSettings) -> Boolean>(
        { it.experimentWeatherAge }, { it.experimentHeatCeiling }, { it.experimentGpsSpeed }, { it.experimentRecalcInactive },
        { it.experimentColorMatrix }, { it.experimentProtocolSix }, { it.experimentTolerantRx },
    )
    private val ones = listOf(
        REauSettings(experimentWeatherAge = true), REauSettings(experimentHeatCeiling = true), REauSettings(experimentGpsSpeed = true),
        REauSettings(experimentRecalcInactive = true), REauSettings(experimentColorMatrix = true), REauSettings(experimentProtocolSix = true),
        REauSettings(experimentTolerantRx = true),
    )
    private val phrases = listOf(
        "weather hidden from the dash after 30 min", "frame ceiling lowered by the battery temperature", "GPS speed for the speed profile",
        "a short inactive of the route shown as a recalculation", "BT.601 limited-range", "protocol 6.0 asked of Android Auto first",
        "tolerant dash silence watch",
    )

    @Test
    fun `all seven are off by default`() {
        assertEquals(List(7) { false }, readers.map { it(REauSettings()) })
    }

    @Test
    fun `sanitized keeps each one, in a debug build and in a release`() {
        for (debug in listOf(true, false)) for ((i, one) in ones.withIndex()) {
            assertEquals("switch $i, debug=$debug", List(7) { it == i }, readers.map { it(one.sanitized(debug)) })
        }
    }

    @Test
    fun `each is stored and read back on its own, and a phone that never stored them reads them off`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        for ((i, one) in ones.withIndex()) {
            store.save(one)
            assertEquals("switch $i alone", List(7) { it == i }, readers.map { it(store.load()) })
            store.save(REauSettings())
            assertEquals(List(7) { false }, readers.map { it(store.load()) })
        }
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 200).apply()
        assertEquals(List(7) { false }, readers.map { it(SettingsStore(prefs).load()) })
    }

    @Test
    fun `the stored choice of the bind retries survives the lock leaving that switch`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putBoolean("experiment_wifi_bind", true).apply()
        assertTrue("whoever had it on keeps the retries on", SettingsStore(prefs).load().experimentWifiBind)
        assertTrue(SettingsStore(prefs).load().sanitized().experimentWifiBind)
    }

    @Test
    fun `the settings line says every one and the experiments line says the ones that are on`() {
        val off = SettingsInUse.line(REauSettings())
        for (name in fields) assertTrue("$name in: $off", "$name=false" in off)
        assertNull(SettingsInUse.experimentsLine(REauSettings()))
        for ((i, one) in ones.withIndex()) {
            assertTrue("$i in the settings line", "${fields[i]}=true" in SettingsInUse.line(one))
            val said = SettingsInUse.experimentsLine(one)!!
            assertTrue(said, said.startsWith("connection experiments ON: ") && phrases[i] in said)
            for ((j, other) in phrases.withIndex()) if (j != i) assertFalse("$other not in: $said", other in said)
        }
        val all = SettingsInUse.experimentsLine(ones.fold(REauSettings()) { acc, one -> merge(acc, one) })!!
        for (phrase in phrases) assertTrue("$phrase in: $all", phrase in all)
    }

    @Test
    fun `the start guard comes on by default and the experiments line does not count it alone`() {
        assertTrue(REauSettings().experimentStartGuard)
        assertTrue("experimentStartGuard=true" in SettingsInUse.line(REauSettings()))
        assertNull(SettingsInUse.experimentsLine(REauSettings()))
        assertNull(SettingsInUse.experimentsLine(REauSettings(experimentStartGuard = true)))
        assertFalse("start guard" in SettingsInUse.experimentsLine(REauSettings(experimentGpsSpeed = true))!!)
    }

    @Test
    fun `the start guard knows each one and the rider's change of one is said in the log`() {
        for ((i, one) in ones.withIndex()) {
            assertEquals(listOf(fields[i]), ExperimentsGuard.experimentsOn(one))
            assertEquals(REauSettings(), ExperimentsGuard.allOff(one))
            assertEquals(listOf("${fields[i]}: false -> true"), REauSettings().changesTo(one))
        }
    }

    @Test
    fun `protocol 6 reaches the Android Auto configuration only with its switch, and 1_7 stays the fallback`() {
        assertFalse(REauSettings().aaConfig.protocolSix)
        assertTrue(REauSettings(experimentProtocolSix = true).aaConfig.protocolSix)
        assertEquals(Messages.MINOR_NEWER, REauSettings(experimentProtocolSix = true).aaConfig.protocolMinor)
        assertNotNull(REauSettings().aaConfig)
    }

    @Test
    fun `the bind retries line no longer speaks of the Wi-Fi lock, and the three languages say the lock left the switch`() {
        val said = SettingsInUse.experimentsLine(REauSettings(experimentWifiBind = true))!!
        assertTrue(said, "bind retries on the same network" in said && "low-latency" !in said && "lock" !in said)
        val folders = mapOf("values" to ("EPERM" to "lock"), "values-pt" to ("EPERM" to "trava"), "values-es" to ("EPERM" to "bloqueo"))
        for ((folder, words) in folders) {
            val sub = Regex("""<string name="exp_wifi_sub">(.*?)</string>""").find(java.io.File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
            assertTrue("$folder: $sub", words.first in sub && words.second in sub)
            assertTrue("$folder says the lock is gone", "5" in sub)
        }
    }

    @Test
    fun `every new row has its title and line in the three languages, and the lines say Experimental`() {
        val names = listOf("weather_age", "heat_ceiling", "gps_speed", "recalc", "color", "protocol_six", "tolerant_rx")
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = java.io.File("src/main/res/$folder/strings.xml").readText()
            for (name in names) {
                assertTrue("$folder exp_${name}_title", xml.contains("<string name=\"exp_${name}_title\">"))
                val sub = Regex("""<string name="exp_${name}_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$folder exp_${name}_sub", sub != null && "Experimental" in sub)
            }
            assertTrue("$folder group", xml.contains("<string name=\"group_dash_experiments\">"))
        }
    }

    private fun merge(a: REauSettings, b: REauSettings) = a.copy(
        experimentWeatherAge = a.experimentWeatherAge || b.experimentWeatherAge, experimentHeatCeiling = a.experimentHeatCeiling || b.experimentHeatCeiling,
        experimentGpsSpeed = a.experimentGpsSpeed || b.experimentGpsSpeed, experimentRecalcInactive = a.experimentRecalcInactive || b.experimentRecalcInactive,
        experimentColorMatrix = a.experimentColorMatrix || b.experimentColorMatrix, experimentProtocolSix = a.experimentProtocolSix || b.experimentProtocolSix,
        experimentTolerantRx = a.experimentTolerantRx || b.experimentTolerantRx,
    )
}
