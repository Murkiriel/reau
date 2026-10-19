package com.reau.app.data

import android.content.SharedPreferences
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [KeystoreBackedPrefs] is the replacement for the deprecated EncryptedSharedPreferences that holds the dash's WiFi
 * password. It has to behave exactly like the SharedPreferences [DashConfig]
 * already talks to, and never leave a value readable on disk. These tests run on the JVM with an in-memory backing store
 * and a software AES-GCM cipher (the real Android Keystore cipher is exercised by KeystoreBackedPrefsDeviceTest).
 */
class KeystoreBackedPrefsTest {

    // ── test doubles ─────────────────────────────────────────────────────────────

    /** A minimal in-memory SharedPreferences (map + editor + listeners), like the framework one for these purposes. */
    class FakePrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = LinkedHashSet<String>()
            private var clear = false
            override fun putString(k: String, v: String?) = apply { puts[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { puts[k] = v }
            override fun putInt(k: String, v: Int) = apply { puts[k] = v }
            override fun putLong(k: String, v: Long) = apply { puts[k] = v }
            override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
            override fun remove(k: String) = apply { removes += k }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() { flush() }
            private fun flush() {
                val changed = LinkedHashSet<String>()
                if (clear) { changed += map.keys; map.clear() }
                for (k in removes) if (map.remove(k) != null) changed += k
                for ((k, v) in puts) { if (v == null) map.remove(k) else map[k] = v; changed += k }
                for (k in changed) for (l in listeners.toList()) l.onSharedPreferenceChanged(this@FakePrefs, k)
            }
        }
    }

    /** Software AES-256-GCM, same construction as the Keystore one, with a key we can swap to simulate a lost key. */
    class SoftCipher(seed: Int = 1) : KeystoreBackedPrefs.ValueCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it * 7 + seed).toByte() }, "AES")
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(plain)
        }
        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            c.updateAAD(aad)
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private val backing = FakePrefs()
    private fun prefs(cipher: KeystoreBackedPrefs.ValueCipher = SoftCipher(), store: FakePrefs = backing) = KeystoreBackedPrefs(store, cipher)

    // ── behaves like SharedPreferences ───────────────────────────────────────────

    @Test
    fun `every supported type round-trips and a missing key gives the default`() {
        val p = prefs()
        p.edit().putString("s", "olá, mundo").putInt("i", -7).putLong("l", 1L shl 40).putFloat("f", 2.5f)
            .putBoolean("b", true).putStringSet("set", mutableSetOf("a", "b")).commit()
        assertEquals("olá, mundo", p.getString("s", null))
        assertEquals(-7, p.getInt("i", 0)); assertEquals(1L shl 40, p.getLong("l", 0)); assertEquals(2.5f, p.getFloat("f", 0f), 0f)
        assertTrue(p.getBoolean("b", false)); assertEquals(setOf("a", "b"), p.getStringSet("set", null))
        assertEquals("d", p.getString("nope", "d")); assertEquals(9, p.getInt("nope", 9)); assertFalse(p.getBoolean("nope", false))
        assertNull(p.getString("nope", null)); assertNull(p.getStringSet("nope", null))
    }

    @Test
    fun `values survive a new instance over the same file and the same key`() {
        prefs().edit().putString("ssid", "Dash-1234").putString("password", "s3cr3t!").apply()
        val again = prefs()
        assertEquals("Dash-1234", again.getString("ssid", null))
        assertEquals("s3cr3t!", again.getString("password", null))
    }

    @Test
    fun `contains, remove and clear`() {
        val p = prefs()
        p.edit().putString("a", "1").putInt("b", 2).commit()
        assertTrue(p.contains("a")); assertFalse(p.contains("zz"))
        p.edit().remove("a").commit()
        assertFalse(p.contains("a")); assertTrue(p.contains("b"))
        p.edit().clear().commit()
        assertFalse(p.contains("b")); assertTrue(backing.map.isEmpty())
    }

    @Test
    fun `clear runs first, so a put in the same edit survives it`() {
        val p = prefs()
        p.edit().putString("old", "x").commit()
        p.edit().putString("new", "y").clear().commit()
        assertFalse(p.contains("old")); assertEquals("y", p.getString("new", null))
    }

    @Test
    fun `putting null removes the key, like the framework`() {
        val p = prefs()
        p.edit().putString("k", "v").putStringSet("s", mutableSetOf("x")).commit()
        p.edit().putString("k", null).putStringSet("s", null).commit()
        assertFalse(p.contains("k")); assertFalse(p.contains("s"))
    }

    @Test
    fun `getAll gives the decrypted values with their types, and a copy`() {
        val p = prefs()
        p.edit().putString("s", "v").putInt("i", 3).putBoolean("b", true).commit()
        val all = p.all
        assertEquals("v", all["s"]); assertEquals(3, all["i"]); assertEquals(true, all["b"])
        assertEquals(3, all.size)
        @Suppress("UNCHECKED_CAST") (all as MutableMap<String, Any?>).clear()
        assertEquals("the copy is detached", 3, p.all.size)
    }

    @Test
    fun `reading a key as another type is a ClassCastException, as in the framework`() {
        val p = prefs()
        p.edit().putString("k", "text").commit()
        try { p.getInt("k", 0); fail("expected ClassCastException") } catch (_: ClassCastException) {}
    }

    @Test
    fun `listeners hear the changed keys with this object, until unregistered`() {
        val p = prefs()
        val heard = ArrayList<String?>(); var from: SharedPreferences? = null
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, key -> heard += key; from = sp }
        p.registerOnSharedPreferenceChangeListener(l)
        p.edit().putString("a", "1").putInt("b", 2).commit()
        p.edit().remove("a").commit()
        assertEquals(listOf<String?>("a", "b", "a"), heard)
        assertTrue("the listener gets the wrapper, not the raw file", from === p)
        p.unregisterOnSharedPreferenceChangeListener(l)
        p.edit().putString("c", "3").commit()
        assertEquals(3, heard.size)
    }

    // ── what is on disk ──────────────────────────────────────────────────────────

    @Test
    fun `no value is readable in the backing file`() {
        val secret = "correct-horse-battery-staple"
        prefs().edit().putString("password", secret).putString("ssid", "MyHotspot-42").putInt("port", 8080).commit()
        assertTrue(backing.map.isNotEmpty())
        for ((k, v) in backing.map) {
            val raw = v.toString()
            assertFalse("$k leaks the password", raw.contains(secret))
            assertFalse("$k leaks the ssid", raw.contains("MyHotspot-42"))
            val decoded = String(java.util.Base64.getDecoder().decode(raw.removePrefix("v1:")), Charsets.ISO_8859_1)
            assertFalse("$k is only base64, not encrypted", decoded.contains(secret) || decoded.contains("MyHotspot-42"))
        }
    }

    @Test
    fun `the same value under the same key encrypts differently every time`() {
        val p = prefs()
        p.edit().putString("k", "same").commit(); val first = backing.map["k"]
        p.edit().putString("k", "same").commit(); val second = backing.map["k"]
        assertNotEquals(first, second)
        assertEquals("same", p.getString("k", null))
    }

    @Test
    fun `a value copied under another key does not decrypt there`() {
        val p = prefs()
        p.edit().putString("password", "s3cr3t").putString("nickname", "bike").commit()
        backing.map["nickname"] = backing.map["password"]        // an attacker (or a bug) swaps the stored blobs
        assertEquals("d", p.getString("nickname", "d"))
    }

    @Test
    fun `a tampered value reads as the default instead of crashing`() {
        val p = prefs()
        p.edit().putString("k", "v").commit()
        val raw = backing.map["k"] as String
        backing.map["k"] = raw.dropLast(3) + "AAA"
        assertEquals("d", p.getString("k", "d"))
        backing.map["k"] = "not even base64 !!"
        assertEquals("d", p.getString("k", "d"))
        assertTrue("getAll skips what it cannot read", p.all.isEmpty())
    }

    @Test
    fun `with another key the old values are unreadable, not garbage`() {
        prefs(SoftCipher(seed = 1)).edit().putString("k", "v").commit()
        val other = prefs(SoftCipher(seed = 2))
        assertEquals("d", other.getString("k", "d"))
        assertTrue(other.all.isEmpty())
    }

    @Test
    fun `the plain-text encoding of a value does not depend on the platform locale`() {
        val p = prefs()
        p.edit().putFloat("f", 1234.5f).putLong("l", 1234567890123L).commit()
        assertEquals(1234.5f, p.getFloat("f", 0f), 0f)
        assertEquals(1234567890123L, p.getLong("l", 0))
    }

    @Test
    fun `the cipher contract itself round-trips and rejects a different AAD`() {
        val c = SoftCipher()
        val blob = c.encrypt("x".toByteArray(), "k".toByteArray())
        assertArrayEquals("x".toByteArray(), c.decrypt(blob, "k".toByteArray()))
        try { c.decrypt(blob, "other".toByteArray()); fail("AAD must be authenticated") } catch (_: Exception) {}
    }
    // ── reads are cheap (DashConfig reads on hot paths) ──────────────────────────

    /** Counts decrypt calls, to see when a read really has to decrypt. */
    private class CountingCipher(private val inner: KeystoreBackedPrefs.ValueCipher) : KeystoreBackedPrefs.ValueCipher {
        var decrypts = 0
        override fun encrypt(plain: ByteArray, aad: ByteArray) = inner.encrypt(plain, aad)
        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray { decrypts++; return inner.decrypt(blob, aad) }
    }

    @Test
    fun `reading the same unchanged value again does not decrypt again`() {
        val c = CountingCipher(SoftCipher())
        val p = KeystoreBackedPrefs(backing, c)
        p.edit().putString("ssid", "Dash-1").putBoolean("b", true).commit()
        repeat(50) { assertEquals("Dash-1", p.getString("ssid", null)); assertTrue(p.getBoolean("b", false)) }
        assertTrue("2 keys, decrypted at most once each, was ${c.decrypts}", c.decrypts <= 2)
    }

    @Test
    fun `a value changed in the backing file is read again, never served stale`() {
        val c = CountingCipher(SoftCipher())
        val p = KeystoreBackedPrefs(backing, c)
        p.edit().putString("k", "one").commit()
        assertEquals("one", p.getString("k", null))
        // another instance (same file, same key) rewrites it
        KeystoreBackedPrefs(backing, SoftCipher()).edit().putString("k", "two").commit()
        assertEquals("two", p.getString("k", null))
        backing.map.remove("k")
        assertEquals("gone", p.getString("k", "gone"))
    }

    @Test
    fun `a cached string set cannot be changed from outside`() {
        val p = prefs()
        p.edit().putStringSet("s", mutableSetOf("a")).commit()
        p.getStringSet("s", null)!!.add("hacked")
        (p.all["s"] as MutableSet<String>).add("hacked-too")
        assertEquals(setOf("a"), p.getStringSet("s", null))
    }
}
