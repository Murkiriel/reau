// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapSsl.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/AapSsl.kt
package com.reau.app.aa

interface AapSsl {
    fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun postHandshakeReset()
    fun performHandshake(connection: AccessoryConnection): Boolean
    fun release()
}
