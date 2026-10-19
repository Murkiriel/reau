package com.reau.app.dash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DashSessionCryptoTest {

    private fun aesEncryptCbc(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(plain)
    }

    @Test
    fun `decrypts a blob this class itself would have produced (round-trip)`() {
        val key = ByteArray(32) { it.toByte() } // a 32-byte "session key"
        val iv = ByteArray(16) { (it * 2).toByte() }
        val plain = "trip=142.3km odo=18321km fuel=63pct".toByteArray(Charsets.UTF_8)

        val ivAndCt = aesEncryptCbc(plain, key, iv)
        val decrypted = DashSession.aesDecryptCbc(ivAndCt, key)

        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun `wrong key fails to decrypt (returns null instead of throwing or garbage)`() {
        val key = ByteArray(32) { it.toByte() }
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(16)
        val ivAndCt = aesEncryptCbc("secret telemetry".toByteArray(), key, iv)

        // PKCS5 padding almost always fails to validate under the wrong key -> null.
        assertNull(DashSession.aesDecryptCbc(ivAndCt, wrongKey))
    }

    @Test
    fun `a blob no longer than the IV (16 bytes) is rejected instead of throwing`() {
        assertNull(DashSession.aesDecryptCbc(ByteArray(16), ByteArray(32)))
        assertNull(DashSession.aesDecryptCbc(ByteArray(5), ByteArray(32)))
        assertNull(DashSession.aesDecryptCbc(ByteArray(0), ByteArray(32)))
    }

    @Test
    fun `garbage ciphertext is rejected instead of throwing`() {
        val random = ByteArray(48) { (it * 37).toByte() } // 16B IV + 32B of noise, not real AES output
        assertNull(DashSession.aesDecryptCbc(random, ByteArray(32)))
    }
}
