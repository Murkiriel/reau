package com.reau.app.dash

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pairing on a real device: written through the Android Keystore, read back by a second instance (as after an
 * app restart), and not readable in the preferences file itself.
 */
@RunWith(AndroidJUnit4::class)
class DashPairingDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun rawFile() = context.getSharedPreferences("dash_pairing", Context.MODE_PRIVATE)

    @Before
    @After
    fun startFromAFreshInstall() {
        rawFile().edit().clear().commit()
    }

    @Test
    fun aFreshInstallLooksForAnyDashWithTheFactoryPassword() {
        val p = DashPairing.open(context)

        assertEquals("RE_", p.ssidPrefix)
        assertEquals("12345678", p.password)
        assertTrue(p.needsDiscovery)
    }

    @Test
    fun thePairingSurvivesARestartAndIsNotReadableInTheFile() {
        DashPairing.open(context).apply {
            ssid = "RE_AB12_345678"
            password = "s3nha-de-teste"
        }

        val again = DashPairing.open(context)
        assertEquals("RE_AB12_345678", again.ssid)
        assertEquals("s3nha-de-teste", again.password)
        assertFalse(again.needsDiscovery)

        val onDisk = rawFile().all
        assertEquals(setOf("ssid", "password"), onDisk.keys)
        val values = onDisk.values.joinToString("|")
        assertTrue("stored in the encrypted layout: $values", onDisk.values.all { it is String && it.startsWith("v1:") })
        assertFalse(values, values.contains("RE_AB12_345678"))
        assertFalse(values, values.contains("s3nha-de-teste"))
    }

    @Test
    fun forgettingTheDashSurvivesARestartToo() {
        DashPairing.open(context).apply {
            ssid = "RE_AB12_345678"
            password = "s3nha-de-teste"
            forget()
        }

        val again = DashPairing.open(context)
        assertTrue(again.needsDiscovery)
        assertEquals("s3nha-de-teste", again.password)
    }
}
