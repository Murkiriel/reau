package com.reau.app.update

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The fingerprint (SHA-256) of the certificate that signs the project's releases, next to the one of the APK that is installed, so the rider
 * can see in "Check for updates" whether this APK carries the project's signature. It reads the phone's own package record and
 * asks nothing of the network. Pure but for [installed], so the JVM tests cover the formatting and the comparison.
 */
internal object SigningCheck {
    /**
     * SHA-256 of the project's release certificate: 64 hexadecimal digits, with or without colons. It is the certificate of the APKs of
     * the Releases 0.1.0 and 0.2.0 and the owner confirmed it as the project's key (13/11/2026); the install page of the site says the same value.
     * Were it empty, the screen would say there is nothing to compare, and the installed one would still be shown.
     */
    const val EXPECTED_SHA256 = "63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"

    private const val HEX_LENGTH = 64
    private const val PAIRS_PER_LINE = 16

    /**
     * What the two fingerprints say together. DEBUG_BUILD: another signature on a debug build, which is signed with the debug key and not
     * with the project's: not an alarm.
     */
    enum class Verdict { MATCH, DIFFERENT, DEBUG_BUILD, NOT_SET, UNREADABLE }

    /** [hex] as 64 lowercase digits without separators, or null when it is not a SHA-256 (colons, spaces and capitals are accepted). */
    fun normalize(hex: String): String? {
        val clean = hex.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        return clean.takeIf { it.length == HEX_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** [hex] as the pairs `AB:CD:…` in two lines of 16 (what `keytool` and `apksigner` print, broken to fit a phone); [hex] itself if it is not a SHA-256. */
    fun display(hex: String): String {
        val clean = normalize(hex) ?: return hex
        return clean.chunked(2).map { it.uppercase() }.chunked(PAIRS_PER_LINE).joinToString("\n") { it.joinToString(":") }
    }

    /** The fingerprint of one certificate, its encoded bytes. */
    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

    /**
     * Whether the installed signers [installed] (the fingerprints, null when they could not be read) carry [expected]. An APK with
     * several signers matches only when the one expected is the only one, since a second signer is not the project's signature. On a
     * [debugBuild] a signature that is not the project's is [Verdict.DEBUG_BUILD] (expected: the debug key signs it); on a release it is [Verdict.DIFFERENT].
     */
    fun verdict(expected: String, installed: List<String>?, debugBuild: Boolean = false): Verdict {
        val signers = installed?.map { normalize(it) }
        val project = normalize(expected)
        return when {
            signers.isNullOrEmpty() || signers.any { it == null } -> Verdict.UNREADABLE
            project == null -> Verdict.NOT_SET
            signers.size == 1 && signers[0] == project -> Verdict.MATCH
            debugBuild -> Verdict.DEBUG_BUILD
            else -> Verdict.DIFFERENT
        }
    }

    /** The fingerprints of the certificates that sign this app on the phone, or null when Android does not say. */
    fun installed(context: Context): List<String>? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        info?.apkContentsSigners?.map { fingerprint(it.toByteArray()) }
    }.getOrNull()
}
