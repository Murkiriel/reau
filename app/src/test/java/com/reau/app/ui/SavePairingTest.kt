package com.reau.app.ui

import android.content.SharedPreferences
import com.reau.app.dash.DashPairing
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.savePairing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Typing the dash's name or password saves through [savePairing] at every letter, and each write is a round of the Keystore on the main
 * thread: only what changed is written. It used to be read from the source of the activity.
 */
class SavePairingTest {
    /** A preferences store that counts the writes (each `edit()` is one round of the Keystore in the real one). */
    private class Counting(private val inner: SharedPreferences) : SharedPreferences by inner {
        var writes = 0
        override fun edit(): SharedPreferences.Editor = inner.edit().also { writes++ }
    }

    private val prefs = Counting(KeystoreBackedPrefsTest.FakePrefs())
    private val pairing = DashPairing(prefs)

    private fun same() = PairingUi(ssid = pairing.ssid, ssidPrefix = pairing.ssidPrefix, password = pairing.password)

    @Test
    fun `typing what is already saved writes nothing`() {
        val typed = same()
        assertEquals(typed, savePairing(pairing, typed))
        assertEquals(0, prefs.writes)
    }

    @Test
    fun `a changed password writes the password and only it`() {
        savePairing(pairing, same().copy(password = "hunter22"))
        assertEquals(1, prefs.writes)
        assertEquals("hunter22", pairing.password)
        assertEquals("RE_", pairing.ssidPrefix)
        assertEquals("", pairing.ssid)
    }

    @Test
    fun `a changed name writes the name and only it`() {
        savePairing(pairing, same().copy(ssid = "RE_AB12_345678"))
        assertEquals(1, prefs.writes)
        assertEquals("RE_AB12_345678", pairing.ssid)
        assertEquals(DashPairing.DEFAULT_PASSWORD, pairing.password)
    }

    @Test
    fun `a changed prefix writes the prefix and only it`() {
        savePairing(pairing, same().copy(ssidPrefix = "RE_AB"))
        assertEquals(1, prefs.writes)
        assertEquals("RE_AB", pairing.ssidPrefix)
    }

    @Test
    fun `a name and a password changed are two writes, and the next letter of each is two more`() {
        val typed = same().copy(ssid = "RE_X", ssidPrefix = "RE_", password = "abc")
        savePairing(pairing, typed)
        assertEquals(2, prefs.writes)
        savePairing(pairing, typed.copy(ssid = "RE_XY", password = "abcd"))
        assertEquals(4, prefs.writes)
        assertEquals("RE_XY" to "abcd", pairing.ssid to pairing.password)
    }
}
