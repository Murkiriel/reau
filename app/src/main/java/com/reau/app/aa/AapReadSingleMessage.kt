// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapReadSingleMessage.kt, commit 0abbe2a.
// Changes: package; a timeout while reading the fragment size or the body ends the
// connection instead of skipping the message. The header is already consumed and a full read that times out has
// consumed part of what follows (DataInputStream.readFully gives the bytes it got no way back), so the next read would
// take bytes from the middle of a TLS record for a header and the session would stay "active" without ever decrypting
// another message. The timeouts are parameters so a test does not wait the real 10 s.
// Ported from headunit-revived (AGPLv3): aap/AapReadSingleMessage.kt
package com.reau.app.aa

internal class AapReadSingleMessage(
    connection: AccessoryConnection,
    ssl: AapSsl,
    handler: AapMessageHandler,
    private val headerTimeoutMs: Int = HEADER_TIMEOUT_MS,
    private val bodyTimeoutMs: Int = BODY_TIMEOUT_MS,
) : AapRead.Base(connection, ssl, handler) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    // 4MB to handle large I-frames.
    private val msgBuffer = ByteArray(4 * 1024 * 1024)
    private val fragmentSizeBuffer = ByteArray(4)
    private val startedMs = System.currentTimeMillis()
    private var lastMessageMs = 0L

    @Suppress("TooGenericExceptionCaught") // a failed message is logged and skipped; the read loop goes on
    override fun doRead(connection: AccessoryConnection): Int {
        try {
            val isSocket = connection is SocketAccessoryConnection
            val timeout = if (isSocket) headerTimeoutMs else 0
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, timeout, true)
            if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) {
                if (headerSize == -1) {
                    AaLog.i("AapRead: Connection closed (EOF). Disconnecting.")
                    noteTheEnd()
                    return -1
                } else if (headerSize == 0) {
                    if (isSocket) {
                        AaLog.w("AapRead: WiFi read timeout (15s) - connection lost.")
                        noteTheEnd()
                        return -1
                    }
                    return 0
                } else {
                    AaLog.e("AapRead: Partial header read. Expected ${AapMessageIncoming.EncryptedHeader.SIZE}, got $headerSize. Skipping.")
                    return 0
                }
            }

            recvHeader.decode()

            if (isMagicGarbage(recvHeader.buf, 0, recvHeader.buf.size)) {
                AaLog.i("AapRead: Magic Garbage detected in header. Clean disconnect.")
                return -2
            }

            if (recvHeader.flags == 0x09) {
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, bodyTimeoutMs, true)
                if (readSize != 4) {
                    if (readSize == -1) {
                        AaLog.i("AapRead: Connection closed during the fragment size read.")
                        return -1
                    }
                    // The header is consumed and the four bytes may be partly consumed: the stream cannot be
                    // framed again from here. The transport ends the session; the receiver dials again.
                    AaLog.w("AapRead: timed out reading the fragment size after a header (got $readSize of 4): the stream cannot be re-framed; disconnecting")
                    return -1
                }
            }

            if (recvHeader.encLen > msgBuffer.size || recvHeader.encLen < 0) {
                AaLog.e("AapRead: Invalid message size (${recvHeader.encLen} bytes). Skipping.")
                return 0
            }

            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.encLen, bodyTimeoutMs, true)
            if (msgSize != recvHeader.encLen) {
                if (msgSize == -1) {
                    AaLog.i("AapRead: Connection closed during body read.")
                    return -1
                }
                // Same as above: a body that did not come whole within the timeout leaves bytes already
                // taken from the socket with no way back, so the next "header" would be read from inside a message.
                AaLog.w("AapRead: body read timed out (${recvHeader.encLen} expected, $msgSize read): the stream cannot be re-framed; disconnecting")
                return -1
            }

            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)
            if (msg == null) {
                if (ssl is AapSslContext && ssl.isUserDisconnect) {
                    AaLog.i("AapRead: Magic Garbage detected in decryption. Triggering clean disconnect.")
                    return -2
                }
                return 0
            }

            handler.handle(msg)
            lastMessageMs = System.currentTimeMillis()
            return 0
        } catch (e: Exception) {
            AaLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        }
    }

    /**
     * What the line that says the link ended could not say: how long the session lived, how long ago
     * the last message came, and what the phone was doing as far as REau knew (the charger, the screen, doze: [AaLog.phoneNow]).
     * Links have ended within seconds of the charger being plugged or unplugged.
     */
    private fun noteTheEnd() {
        val now = System.currentTimeMillis()
        val last = if (lastMessageMs == 0L) "no message came" else "the last message came ${now - lastMessageMs} ms before"
        val phone = AaLog.phoneNow?.invoke()?.let { "; phone: $it" }.orEmpty()
        AaLog.i("AapRead: the link ended after ${(now - startedMs) / MS_PER_S} s; $last$phone")
    }

    private fun isMagicGarbage(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length < 4) return false
        for (i in 0 until 4.coerceAtMost(length)) {
            if (buffer[start + i] != 0xFF.toByte()) return false
        }
        return true
    }

    companion object {
        /** How long a header may take to arrive on the socket: Android Auto pings well inside this. */
        const val HEADER_TIMEOUT_MS = 15_000

        /** How long the rest of a message (the fragment size, the body) may take after its header. */
        const val BODY_TIMEOUT_MS = 10_000
        private const val MS_PER_S = 1000
    }
}
