// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AccessoryConnection.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): connection/AccessoryConnection.kt
package com.reau.app.aa

interface AccessoryConnection {
    val isSingleMessage: Boolean
    fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int
    fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int
    val isConnected: Boolean
    fun connect(): Boolean
    fun disconnect()
}
