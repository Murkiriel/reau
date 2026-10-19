package com.reau.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [KeystoreBackedPrefs] against the REAL Android Keystore and a real SharedPreferences file (the JVM tests cover the
 * SharedPreferences contract with a software cipher). What only a device can show: the Keystore key is created and
 * reused, a new instance (a new process, in practice) reads what an older one wrote, and the XML on disk holds no
 * readable value.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreBackedPrefsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = "kbp_device_test"
    private val alias = "reau_prefs_test"
    private val otherAlias = "reau_prefs_test_other"

    private fun open(a: String = alias) = KeystoreBackedPrefs.open(context, file, a)
    private fun xml(): String = File(context.applicationInfo.dataDir, "shared_prefs/$file.xml").readText()

    @Before fun setUp() = clean()
    @After fun tearDown() = clean()

    private fun clean() {
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.applicationInfo.dataDir, "shared_prefs/$file.xml").delete()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (a in listOf(alias, otherAlias)) if (ks.containsAlias(a)) ks.deleteEntry(a)
    }

    @Test
    fun valuesRoundTripAndSurviveANewInstance() {
        open().edit().putString("ssid", "Dash-1234").putString("password", "s3cr3t!").putInt("port", 9000)
            .putBoolean("autoConnect", true).putLong("t", 1L shl 41).putFloat("f", 3.5f).putStringSet("s", mutableSetOf("a", "b")).commit()
        val again = open()
        assertEquals("Dash-1234", again.getString("ssid", null))
        assertEquals("s3cr3t!", again.getString("password", null))
        assertEquals(9000, again.getInt("port", 0)); assertTrue(again.getBoolean("autoConnect", false))
        assertEquals(1L shl 41, again.getLong("t", 0)); assertEquals(3.5f, again.getFloat("f", 0f), 0f)
        assertEquals(setOf("a", "b"), again.getStringSet("s", null))
    }

    @Test
    fun theFileOnDiskHoldsNoReadableValue() {
        open().edit().putString("password", "correct-horse-battery-staple").putString("ssid", "MyHotspot-42").commit()
        val text = xml()
        assertTrue("the key names are there", text.contains("password") && text.contains("ssid"))
        assertFalse(text.contains("correct-horse-battery-staple"))
        assertFalse(text.contains("MyHotspot-42"))
        assertTrue(text.contains("v1:"))
    }

    @Test
    fun aDifferentKeystoreKeyCannotReadTheValues() {
        open().edit().putString("password", "s3cr3t").commit()
        val other = open(otherAlias)
        assertEquals("d", other.getString("password", "d"))
        assertTrue(other.all.isEmpty())
        assertEquals("the original key still reads it", "s3cr3t", open().getString("password", null))
    }

    @Test
    fun aLostKeyReadsAsDefaultsInsteadOfCrashing() {
        open().edit().putString("password", "s3cr3t").commit()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(alias)                                   // e.g. a Keystore reset by the OEM or a lock-screen change
        val p = open()
        assertEquals("d", p.getString("password", "d"))
        p.edit().putString("password", "new").commit()          // and it can be written again (a fresh key is created)
        assertEquals("new", p.getString("password", null))
    }

    @Test
    fun theCipherRejectsAWrongAssociatedData() {
        val c = KeystoreBackedPrefs.KeystoreAesGcmCipher(alias)
        val blob = c.encrypt("hello".toByteArray(), "k".toByteArray())
        assertEquals("hello", String(c.decrypt(blob, "k".toByteArray())))
        try { c.decrypt(blob, "other".toByteArray()); fail("AAD must be authenticated") } catch (_: Exception) {}
        assertNull(runCatching { c.decrypt(blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, "k".toByteArray()) }.getOrNull())
    }
}
