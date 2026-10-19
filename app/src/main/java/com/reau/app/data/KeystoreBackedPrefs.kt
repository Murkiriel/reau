package com.reau.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SharedPreferences] whose values are encrypted with AES-256-GCM under a key that lives in the Android Keystore —
 * what keeps the dash's WiFi name and password (see DashPairing).
 *
 * Layout on disk (a normal SharedPreferences file, [backing]): key names stay in the clear, every value is the string
 * `v1:` + base64([ValueCipher] output). The plaintext is one type byte followed by the value in binary, so nothing
 * depends on the platform locale. The key name is bound to its value as GCM associated data (`k1:<key>`): a blob copied
 * under another key does not decrypt there. A value that cannot be read (tampered, a lost or different key) reads as the
 * caller's default and is skipped by [getAll]; it never throws, because DashConfig must not crash app startup over it.
 *
 * Behaves like the framework class where DashConfig can tell: a missing key gives the default, reading a key as the wrong
 * type is a [ClassCastException], putting null removes, `clear()` runs before the puts of the same edit, and listeners are
 * called with THIS object.
 */
@Suppress(
    // The SharedPreferences interface alone has 10 methods; and "cannot be read" must end as the default value, never a
    // crash (a lost Keystore key is an expected situation), so the exceptions are caught wide and deliberately dropped.
    "TooManyFunctions", "TooGenericExceptionCaught", "SwallowedException",
)
class KeystoreBackedPrefs(private val backing: SharedPreferences, private val cipher: ValueCipher) : SharedPreferences {

    /** Authenticated encryption of one value: [encrypt] returns iv + ciphertext, [decrypt] throws if [aad] or the blob is wrong. */
    interface ValueCipher {
        fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
        fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
    }

    private val listeners = LinkedHashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    /** What a stored blob decoded to. Keyed by the raw stored string, so a changed file value is never served stale:
     *  DashConfig reads its settings on hot paths, and a Keystore decrypt per read would be far too slow. */
    private class Decoded(val raw: String, val value: Any)
    private val decoded = java.util.concurrent.ConcurrentHashMap<String, Decoded>()

    // ── reading ──────────────────────────────────────────────────────────────────

    /** The decoded value of [key], or null when absent or unreadable. */
    private fun read(key: String): Any? {
        val raw = try { backing.getString(key, null) } catch (e: ClassCastException) { null } ?: return null   // not one of ours
        return decodeCached(key, raw)
    }

    private fun decodeCached(key: String, raw: String): Any? {
        decoded[key]?.let { if (it.raw == raw) return it.value }
        val v = decode(key, raw)
        if (v != null) decoded[key] = Decoded(raw, v) else decoded.remove(key)
        return v
    }

    private fun decode(key: String, raw: String): Any? = try {
        require(raw.startsWith(PREFIX))
        val plain = cipher.decrypt(Base64.getDecoder().decode(raw.substring(PREFIX.length)), aad(key))
        val input = DataInputStream(ByteArrayInputStream(plain))
        when (input.readByte()) {
            T_STRING -> String(plain, 1, plain.size - 1, Charsets.UTF_8)
            T_INT -> input.readInt()
            T_LONG -> input.readLong()
            T_FLOAT -> input.readFloat()
            T_BOOL -> input.readBoolean()
            T_SET -> HashSet<String>().also { s -> repeat(input.readInt()) { s += readString(input) } }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    override fun getAll(): MutableMap<String, *> {
        val out = HashMap<String, Any?>()
        for ((k, v) in backing.all) if (v is String) decodeCached(k, v)?.let { out[k] = if (it is Set<*>) HashSet(it) else it }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> typed(key: String, def: T, cls: Class<*>): T {
        val v = read(key) ?: return def
        if (!cls.isInstance(v)) throw ClassCastException("${v.javaClass.simpleName} cannot be cast to ${cls.simpleName} (key $key)")
        return v as T
    }

    override fun getString(key: String, defValue: String?): String? = typed(key, defValue, String::class.java)
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (typed(key, defValues as Set<String>?, Set::class.java))?.let { HashSet(it) }
    override fun getInt(key: String, defValue: Int): Int = typed(key, defValue, Int::class.javaObjectType)
    override fun getLong(key: String, defValue: Long): Long = typed(key, defValue, Long::class.javaObjectType)
    override fun getFloat(key: String, defValue: Float): Float = typed(key, defValue, Float::class.javaObjectType)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = typed(key, defValue, Boolean::class.javaObjectType)
    override fun contains(key: String): Boolean = backing.contains(key)

    // ── writing ──────────────────────────────────────────────────────────────────

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private object Removed

    @Suppress("TooManyFunctions")   // the Editor interface has 10 methods
    private inner class EditorImpl : SharedPreferences.Editor {
        private val ops = LinkedHashMap<String, Any?>()      // key -> value | Removed
        private var clear = false

        private fun put(key: String, value: Any?) = apply { ops[key] = value ?: Removed }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.let { HashSet(it) })
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = apply { ops[key] = Removed }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean = write()?.commit() ?: false
        override fun apply() { write()?.apply() }

        /** The backing editor holding this edit, or null when a value could not be encrypted (nothing is written then). */
        private fun write(): SharedPreferences.Editor? {
            val e = backing.edit()
            try {
                if (clear) e.clear()
                for ((k, v) in ops) {
                    if (v === Removed) e.remove(k)
                    else e.putString(k, PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(encode(v!!), aad(k))))
                }
            } catch (ex: Exception) {
                Log.w(TAG, "could not encrypt a preference; the edit was dropped: ${ex.message}")
                return null
            }
            return e
        }
    }

    // ── listeners ────────────────────────────────────────────────────────────────

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> listener.onSharedPreferenceChanged(this, key) }
        synchronized(listeners) { listeners.put(listener, wrapper)?.let { backing.unregisterOnSharedPreferenceChangeListener(it) } }
        backing.registerOnSharedPreferenceChangeListener(wrapper)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapper = synchronized(listeners) { listeners.remove(listener) } ?: return
        backing.unregisterOnSharedPreferenceChangeListener(wrapper)
    }

    // ── encoding ─────────────────────────────────────────────────────────────────

    private fun aad(key: String): ByteArray = "k1:$key".toByteArray(Charsets.UTF_8)

    private fun encode(v: Any): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        when (v) {
            is String -> { out.writeByte(T_STRING.toInt()); out.write(v.toByteArray(Charsets.UTF_8)) }
            is Int -> { out.writeByte(T_INT.toInt()); out.writeInt(v) }
            is Long -> { out.writeByte(T_LONG.toInt()); out.writeLong(v) }
            is Float -> { out.writeByte(T_FLOAT.toInt()); out.writeFloat(v) }
            is Boolean -> { out.writeByte(T_BOOL.toInt()); out.writeBoolean(v) }
            is Set<*> -> {
                out.writeByte(T_SET.toInt()); out.writeInt(v.size)
                for (s in v) { val b = (s as String).toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
            }
            else -> throw IllegalArgumentException("unsupported preference type ${v.javaClass}")
        }
        return bytes.toByteArray()
    }

    private fun readString(input: DataInputStream): String {
        val b = ByteArray(input.readInt()); input.readFully(b); return String(b, Charsets.UTF_8)
    }

    // ── the real cipher ──────────────────────────────────────────────────────────

    /** AES-256-GCM with a non-exportable key in the Android Keystore (created on first use under [alias]). */
    class KeystoreAesGcmCipher(private val alias: String = DEFAULT_ALIAS) : ValueCipher {
        /** The Keystore handle, kept after the first lookup (a Keystore round trip per read is slow); dropped when a
         *  call fails, so a key that was deleted or replaced underneath us is looked up (or created) again. */
        @Volatile private var cachedKey: SecretKey? = null

        private fun key(): SecretKey = cachedKey ?: loadOrCreateKey().also { cachedKey = it }

        private fun loadOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = withKey { k ->
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)                  // the Keystore picks a fresh random IV
            c.updateAAD(aad)
            c.iv + c.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = withKey { k ->
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob.copyOfRange(0, IV_BYTES)))
            c.updateAAD(aad)
            c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }

        /** Runs [op] with the cached key; if it fails, forgets the cached key and tries once more with a fresh lookup. */
        private fun <T> withKey(op: (SecretKey) -> T): T = try {
            op(key())
        } catch (e: Exception) {
            cachedKey = null
            op(key())
        }

        companion object {
            const val DEFAULT_ALIAS = "reau_prefs_v1"
            private const val IV_BYTES = 12
        }
    }

    companion object {
        private const val TAG = "KeystoreBackedPrefs"
        private const val PREFIX = "v1:"
        private const val T_STRING: Byte = 1
        private const val T_INT: Byte = 2
        private const val T_LONG: Byte = 3
        private const val T_FLOAT: Byte = 4
        private const val T_BOOL: Byte = 5
        private const val T_SET: Byte = 6

        /** The prefs file [fileName] of this app, values encrypted with the Keystore key [alias]. */
        fun open(context: Context, fileName: String, alias: String = KeystoreAesGcmCipher.DEFAULT_ALIAS): KeystoreBackedPrefs =
            KeystoreBackedPrefs(context.getSharedPreferences(fileName, Context.MODE_PRIVATE), KeystoreAesGcmCipher(alias))
    }
}
