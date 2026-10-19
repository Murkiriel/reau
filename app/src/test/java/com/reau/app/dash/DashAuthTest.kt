package com.reau.app.dash

import com.reau.app.dash.protocol.Tlv
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

class DashAuthTest {

    private fun rsaKeyPair(): Pair<RSAPublicKey, RSAPrivateKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(1024)
        val pair = gen.generateKeyPair()
        return pair.public as RSAPublicKey to pair.private as RSAPrivateKey
    }

    private fun modulusTlv(pub: RSAPublicKey) = Tlv(0x07, 0x00, pub.modulus.toByteArray())
    private fun exponentTlv(pub: RSAPublicKey) = Tlv(0x07, 0x03, pub.publicExponent.toByteArray())
    private fun confirmTlv(ok: Boolean) = Tlv(0x07, 0x01, byteArrayOf(if (ok) 0x01 else 0x00))

    @Test
    fun `a TLV with a different type is ignored`() {
        val auth = DashAuth("RE_TEST")
        assertEquals(AuthEvent.None, auth.ingest(Tlv(0x05, 0x00, byteArrayOf(1))))
    }

    @Test
    fun `modulus alone is not enough to send the key`() {
        val (pub, _) = rsaKeyPair()
        val auth = DashAuth("RE_TEST")
        assertEquals(AuthEvent.None, auth.ingest(modulusTlv(pub)))
    }

    @Test
    fun `exponent alone is not enough to send the key`() {
        val (pub, _) = rsaKeyPair()
        val auth = DashAuth("RE_TEST")
        assertEquals(AuthEvent.None, auth.ingest(exponentTlv(pub)))
    }

    @Test
    fun `receiving both modulus and exponent, in either order, triggers exactly one SendKey`() {
        val (pub, _) = rsaKeyPair()

        val authModulusFirst = DashAuth("RE_TEST")
        assertEquals(AuthEvent.None, authModulusFirst.ingest(modulusTlv(pub)))
        assertTrue(authModulusFirst.ingest(exponentTlv(pub)) is AuthEvent.SendKey)

        val authExponentFirst = DashAuth("RE_TEST")
        assertEquals(AuthEvent.None, authExponentFirst.ingest(exponentTlv(pub)))
        assertTrue(authExponentFirst.ingest(modulusTlv(pub)) is AuthEvent.SendKey)
    }

    @Test
    fun `the key is only ever sent once per attempt, even if modulus arrives again`() {
        val (pub, _) = rsaKeyPair()
        val auth = DashAuth("RE_TEST")
        auth.ingest(modulusTlv(pub))
        assertTrue(auth.ingest(exponentTlv(pub)) is AuthEvent.SendKey)

        // Re-ingesting the modulus must NOT re-trigger SendKey (keySent latch).
        assertEquals(AuthEvent.None, auth.ingest(modulusTlv(pub)))
    }

    @Test
    fun `reset re-arms the state machine so a new attempt sends a fresh key`() {
        val (pub, _) = rsaKeyPair()
        val auth = DashAuth("RE_TEST")
        auth.ingest(modulusTlv(pub))
        auth.ingest(exponentTlv(pub))

        auth.reset()
        assertEquals(AuthEvent.None, auth.ingest(modulusTlv(pub)))
        assertTrue(auth.ingest(exponentTlv(pub)) is AuthEvent.SendKey)
    }

    @Test
    fun `07 01 with byte 01 confirms, anything else rejects`() {
        val auth = DashAuth("RE_TEST")
        assertEquals(AuthEvent.Confirmed, auth.ingest(confirmTlv(true)))
        assertEquals(AuthEvent.Rejected, auth.ingest(confirmTlv(false)))
        assertEquals(AuthEvent.Rejected, auth.ingest(Tlv(0x07, 0x01, byteArrayOf())))
    }

    @Test
    fun `a degenerate modulus never crashes ingest, and a later valid retry still succeeds`() {
        // A corrupt/truncated packet (or any other device chattering on the dash's own broadcast
        // WiFi) can hand us an empty/zero modulus — BigInteger(1, bytes) never throws for that, but
        // KeyFactory.generatePublic/Cipher deep inside buildKeyPacket does (InvalidKeySpecException:
        // "RSA keys must be at least 512 bits long"). ingest() is called straight from DashSession's
        // RX loop with no surrounding try/catch, so this must never escape uncaught.
        val (pub, _) = rsaKeyPair()
        val auth = DashAuth("RE_TEST")

        assertEquals(AuthEvent.None, auth.ingest(Tlv(0x07, 0x00, byteArrayOf())))
        assertEquals(AuthEvent.None, auth.ingest(exponentTlv(pub)))

        // A legitimate retransmit of both halves afterwards must still work — the failed attempt
        // must not leave keySent latched or stale state behind.
        assertEquals(AuthEvent.None, auth.ingest(modulusTlv(pub)))
        assertTrue(auth.ingest(exponentTlv(pub)) is AuthEvent.SendKey)
    }

    @Test
    fun `an unknown 07 sub-type is ignored without disturbing accumulated state`() {
        val (pub, _) = rsaKeyPair()
        val auth = DashAuth("RE_TEST")
        auth.ingest(modulusTlv(pub))
        assertEquals(AuthEvent.None, auth.ingest(Tlv(0x07, 0x99, byteArrayOf(1))))
        // The modulus we ingested earlier must still be remembered.
        assertTrue(auth.ingest(exponentTlv(pub)) is AuthEvent.SendKey)
    }

    @Test
    fun `the sent key packet actually decrypts to the SSID followed by a 32-byte AES key`() {
        val (pub, priv) = rsaKeyPair()
        val ssid = "RE_1234ABCD"
        val auth = DashAuth(ssid)

        auth.ingest(modulusTlv(pub))
        val event = auth.ingest(exponentTlv(pub)) as AuthEvent.SendKey

        // authSendKey() = fixed 21-byte header + 128-byte RSA ciphertext (see DashCommandsTest).
        val ciphertext = event.packet.copyOfRange(event.packet.size - 128, event.packet.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, priv)
        val plain = cipher.doFinal(ciphertext)

        assertEquals(ssid.toByteArray(Charsets.UTF_8).size + 32, plain.size)
        assertArrayEquals(ssid.toByteArray(Charsets.UTF_8), plain.copyOfRange(0, ssid.length))

        val aesKeyFromPacket = plain.copyOfRange(ssid.length, plain.size)
        assertArrayEquals(aesKeyFromPacket, auth.sessionKey)
    }

    @Test
    fun `sessionKey is null until a key has actually been sent`() {
        val auth = DashAuth("RE_TEST")
        assertNull(auth.sessionKey)
    }

    @Test
    fun `re-parsing the same modulus via KeyFactory still yields a working RSA public key`() {
        // Sanity check on the TLV<->BigInteger<->PublicKey path used by buildKeyPacket,
        // independent of DashAuth itself.
        val (pub, _) = rsaKeyPair()
        val rebuilt = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(pub.modulus, pub.publicExponent)) as RSAPublicKey
        assertEquals(pub.modulus, rebuilt.modulus)
        assertEquals(pub.publicExponent, rebuilt.publicExponent)
    }
}
