// Copied from open-headunit (AGPL-3.0): app/src/main/java/com/andrerinas/openheadunit/aap/HandshakeMessagePolicy.kt, commit 93c065dd.
// Changes: package only.
package com.reau.app.aa

/**
 * A slow peer answers every version request it was sent, an abandoned handshake's included, and
 * the extra answers land in the TLS phase. The SSLEngine fails on them, so the reader skips them.
 */
object HandshakeMessagePolicy {
    /** [header] is the 6-byte AAP header: channel, flags, length (2), type (2). */
    fun isLateVersionResponse(header: ByteArray): Boolean =
        header.size >= 6 &&
            header[0] == 0.toByte() &&
            header[4] == 0.toByte() &&
            header[5] == 2.toByte()
}
