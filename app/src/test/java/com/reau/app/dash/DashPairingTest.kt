package com.reau.app.dash

import com.reau.app.data.KeystoreBackedPrefs
import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import com.reau.app.data.KeystoreBackedPrefsTest.SoftCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app remembers about the rider's dash: which Wi-Fi it is and its password.
 *
 * Every dash is an access point named `RE_…` with the panel's default passphrase `12345678`. So a fresh install can already
 * find a dash by prefix; once one was joined its exact name is kept, because the dash checks that name inside the
 * encrypted handshake.
 */
class DashPairingTest {
    private val backing = FakePrefs()
    private fun pairing() = DashPairing(KeystoreBackedPrefs(backing, SoftCipher()))

    @Test
    fun `a fresh install looks for any dash with the factory password`() {
        val p = pairing()

        assertEquals("RE_", p.ssidPrefix)
        assertEquals("12345678", p.password)
        assertEquals("", p.ssid)
        assertTrue("no dash known yet: discover by prefix", p.needsDiscovery)
    }

    @Test
    fun `the dash that was joined is remembered across restarts`() {
        pairing().apply {
            ssid = "RE_AB12_345678"
            password = "s3nha-de-teste"
        }

        val again = pairing()

        assertEquals("RE_AB12_345678", again.ssid)
        assertEquals("s3nha-de-teste", again.password)
        assertFalse(again.needsDiscovery)
    }

    @Test
    fun `forgetting the dash drops its name and keeps the password and the prefix`() {
        val p = pairing().apply {
            ssidPrefix = "RE_H"
            ssid = "RE_AB12_345678"
            password = "s3nha-de-teste"
        }

        p.forget()

        assertEquals("", p.ssid)
        assertTrue(p.needsDiscovery)
        assertEquals("s3nha-de-teste", p.password)
        assertEquals("RE_H", p.ssidPrefix)
    }

    @Test
    fun `a blank prefix or password falls back to the factory one`() {
        val p = pairing().apply {
            ssidPrefix = "  "
            password = ""
        }

        assertEquals("a blank prefix would match every network in range", "RE_", p.ssidPrefix)
        assertEquals("12345678", p.password)
    }

    @Test
    fun `the name is kept as the dash spells it, minus stray spaces`() {
        val p = pairing().apply { ssid = "  RE_AB12_345678 \n" }

        assertEquals("RE_AB12_345678", p.ssid)
    }

    @Test
    fun `neither the name nor the password is readable in the stored file`() {
        pairing().apply {
            ssid = "RE_AB12_345678"
            password = "s3nha-de-teste"
        }

        val onDisk = backing.map.values.joinToString("|")
        assertEquals("the name and the password", 2, backing.map.size)
        assertFalse(onDisk, onDisk.contains("RE_AB12_345678"))
        assertFalse(onDisk, onDisk.contains("s3nha-de-teste"))
    }
}
