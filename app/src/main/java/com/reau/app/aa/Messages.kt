// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/Messages.kt, commit 0abbe2a.
// Changes: package only; the minor version chosen per session, and the answer read for the log.
// Ported from headunit-revived (AGPLv3): aap/protocol/messages/Messages.kt
package com.reau.app.aa

object Messages {
    const val DEF_BUFFER_LENGTH = 131080

    /** The protocol REau has always announced, 1.2. */
    const val MINOR_DEFAULT = 2

    /**
     * The test: 1.7. Android Auto gates some features on the version the head unit asks for
     * (aa-proxy-rs); the route's total distance (NavigationCurrentPosition) never came with 1.2.
     */
    const val MINOR_NEWER = 7

    private const val VERSION_ANSWER_SIZE = 12
    private const val MAJOR_AT = 6
    private const val MINOR_AT = 8
    private const val STATUS_AT = 10

    val versionRequest: ByteArray
        get() = versionRequestFor(MINOR_DEFAULT)

    /** The version request for protocol 1.[minor]: major and minor as two 16-bit numbers. */
    fun versionRequestFor(minor: Int): ByteArray = createRawMessage(0, 3, 1, byteArrayOf(0, 1, 0, minor.toByte()))

    /** The version Android Auto answered in the first [length] bytes of [buffer], for the log: "1.7, status 0". */
    fun versionAnswer(buffer: ByteArray, length: Int): String {
        if (length < VERSION_ANSWER_SIZE) return "too short"
        fun number(at: Int) = (buffer[at].toInt() and 0xFF shl 8) or (buffer[at + 1].toInt() and 0xFF)
        return "${number(MAJOR_AT)}.${number(MINOR_AT)}, status ${number(STATUS_AT).toShort()}"
    }

    // byte ac_buf [] = {0, 3, 0, 4, 0, 4, 8, 0};
    val statusOk: ByteArray
        get() = createRawMessage(0, 3, 4, byteArrayOf(8, 0), 2)

    fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray): ByteArray =
        createRawMessage(chan, flags, type, data, data.size)

    private fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray, size: Int): ByteArray {
        val total = 6 + size
        val buffer = ByteArray(total)

        buffer[0] = chan.toByte()
        buffer[1] = flags.toByte()
        Utils.intToBytes(size + 2, 2, buffer)
        Utils.intToBytes(type, 4, buffer)

        System.arraycopy(data, 0, buffer, 6, size)
        return buffer
    }
}
