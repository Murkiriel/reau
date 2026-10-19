// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapMessageIncoming.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/AapMessageIncoming.kt
package com.reau.app.aa

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    : AapMessage(header.chan, header.flags.toByte(), typeOf(ba.data), PAYLOAD_OFFSET, ba.limit, ba.data) {

    internal class EncryptedHeader {
        var chan: Int = 0
        var flags: Int = 0
        var encLen: Int = 0
        var msgType: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt()
            this.flags = buf[1].toInt()
            // Encoded length of bytes to be decrypted (minus 4/8 byte headers)
            this.encLen = Utils.bytesToInt(buf, 2, true)
        }

        companion object {
            const val SIZE = 4
        }
    }

    companion object {
        fun decrypt(header: EncryptedHeader, offset: Int, buf: ByteArray, ssl: AapSsl): AapMessage? {
            if (header.flags and 0x08 != 0x08) {
                AaLog.e(
                    "WRONG FLAG: enc_len: %d  chan: %d %s flags: 0x%02x  msg_type: 0x%02x %s",
                    header.encLen, header.chan, Channel.name(header.chan), header.flags, header.msgType,
                    MsgType.name(header.msgType, header.chan)
                )
                return null
            }

            val ba = ssl.decrypt(offset, header.encLen, buf) ?: return null

            if (ba.data.size < minimumPayload(header)) {
                AaLog.e("Decrypted payload too short: " + ba.data.size)
                return null
            }

            val msg = AapMessageIncoming(header, ba)
            if (AaLog.LOG_VERBOSE) AaLog.d("RECV: %s", msg.toString())
            return msg
        }

        /** Where the message type starts inside the decrypted payload. */
        private const val PAYLOAD_OFFSET = 2

        /** Bit 0 of the flags: set on a whole message and on the first piece of a split one (flags 11 and 9), clear on 8 and 10. */
        private const val FIRST_PIECE_FLAG = 0x01

        /**
         * The fewest decrypted bytes a message can have: two, for the message type. A middle or last piece of a split
         * video frame (flags 8 and 10) is raw continuation, with no type, so its tail can be a single byte; refusing it
         * left the run without its end and broke the frame (TRUNCATED_PREVIOUS, then a keyframe asked for).
         */
        private fun minimumPayload(header: EncryptedHeader): Int =
            if (header.chan == Channel.ID_VID && header.flags and FIRST_PIECE_FLAG == 0) 1 else 2

        /** The message type is the first two bytes; a one-byte continuation has none. */
        private fun typeOf(data: ByteArray): Int = if (data.size >= 2) Utils.bytesToInt(data, 0, true) else 0
    }
}
