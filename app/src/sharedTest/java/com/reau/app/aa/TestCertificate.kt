package com.reau.app.aa

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Test helper: an RSA key pair with a self-signed certificate, made on the spot. Android has no public API to
 * build a certificate, so the few DER structures it takes are written out by hand here.
 */
class TestCertificate(commonName: String) {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val certificate: X509Certificate

    init {
        val algorithm = sequence(SHA256_WITH_RSA_OID, NULL)
        val name = sequence(set(sequence(COMMON_NAME_OID, tlv(TAG_UTF8_STRING, commonName.toByteArray(Charsets.UTF_8)))))
        val validity = sequence(utcTime("250101000000Z"), utcTime("450101000000Z"))
        val toBeSigned = sequence(
            tlv(TAG_EXPLICIT_0, byteArrayOf(TAG_INTEGER.toByte(), 1, 2)), // version 3
            byteArrayOf(TAG_INTEGER.toByte(), 1, 1), // serial number 1
            algorithm,
            name, // issuer
            validity,
            name, // subject: the same, it is self-signed
            keyPair.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(toBeSigned)
            sign()
        }
        val der = sequence(toBeSigned, algorithm, tlv(TAG_BIT_STRING, byteArrayOf(0) + signature))
        certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private companion object {
        const val TAG_INTEGER = 0x02
        const val TAG_BIT_STRING = 0x03
        const val TAG_UTF8_STRING = 0x0C
        const val TAG_UTC_TIME = 0x17
        const val TAG_SEQUENCE = 0x30
        const val TAG_SET = 0x31
        const val TAG_EXPLICIT_0 = 0xA0

        val NULL = byteArrayOf(0x05, 0x00)

        /** 1.2.840.113549.1.1.11 */
        val SHA256_WITH_RSA_OID = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)

        /** 2.5.4.3 */
        val COMMON_NAME_OID = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

        fun sequence(vararg parts: ByteArray) = tlv(TAG_SEQUENCE, concat(parts))
        fun set(vararg parts: ByteArray) = tlv(TAG_SET, concat(parts))
        fun utcTime(text: String) = tlv(TAG_UTC_TIME, text.toByteArray(Charsets.US_ASCII))

        fun concat(parts: Array<out ByteArray>): ByteArray = ByteArrayOutputStream().also { out -> parts.forEach(out::write) }.toByteArray()

        /** Tag, DER length (short form under 128, else the count of length bytes and the length), content. */
        fun tlv(tag: Int, content: ByteArray): ByteArray {
            val length = content.size
            val header = when {
                length < 0x80 -> byteArrayOf(tag.toByte(), length.toByte())
                length < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
                else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), length.toByte())
            }
            return header + content
        }
    }
}
