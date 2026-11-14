// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/Messages.kt, commit 0abbe2a.
// Changes: package only; the minor version chosen per session, and the answer read for the log.
// Ported from headunit-revived (AGPLv3): aap/protocol/messages/Messages.kt
package com.reau.app.aa

object Messages {
    const val DEF_BUFFER_LENGTH = 131080

    /** The old protocol, 1.2: what REau announced until 1.7 became the fixed choice (28/10/2026); only the fallback of [AaConfig] now. */
    const val MINOR_DEFAULT = 2

    /**
     * The protocol REau announces: 1.7. Android Auto gates some features on the version the head unit asks for
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
    fun versionRequestFor(minor: Int): ByteArray = versionRequestFor(1, minor)

    /** The version request for protocol [major].[minor]: both as 16-bit numbers (a number under 256 in the low byte). */
    fun versionRequestFor(major: Int, minor: Int): ByteArray = createRawMessage(0, 3, 1, byteArrayOf(0, major.toByte(), 0, minor.toByte()))

    /** The major and minor Android Auto answered in the first [length] bytes of [buffer]; null when the answer is too short. */
    fun versionAnswered(buffer: ByteArray, length: Int): Pair<Int, Int>? {
        fun number(at: Int) = (buffer[at].toInt() and 0xFF shl 8) or (buffer[at + 1].toInt() and 0xFF)
        return if (length < VERSION_ANSWER_SIZE) null else number(MAJOR_AT) to number(MINOR_AT)
    }

    /** The version Android Auto answered in the first [length] bytes of [buffer], for the log: "1.7, status 0". */
    fun versionAnswer(buffer: ByteArray, length: Int): String {
        if (length < VERSION_ANSWER_SIZE) return "too short"
        fun number(at: Int) = (buffer[at].toInt() and 0xFF shl 8) or (buffer[at + 1].toInt() and 0xFF)
        return "${number(MAJOR_AT)}.${number(MINOR_AT)}, status ${number(STATUS_AT).toShort()}"
    }

    /**
     * The status of a version answer when it is not 0 (Android Auto refused the version asked for), null when it is 0 or the answer is too
     * short to carry one. Only for the log: the handshake goes on as it always did, and a refused version fails at the TLS step.
     */
    fun versionRefusal(buffer: ByteArray, length: Int): Int? {
        if (length < VERSION_ANSWER_SIZE) return null
        val status = ((buffer[STATUS_AT].toInt() and 0xFF shl 8) or (buffer[STATUS_AT + 1].toInt() and 0xFF)).toShort().toInt()
        return status.takeIf { it != 0 }
    }

    /** The log's line for a version answer with [status] not 0, asked for as 1.[minor]. */
    fun versionRefusalLine(status: Int, minor: Int): String = versionRefusalLine(status, 1, minor)

    /** The log's line for a version answer with [status] not 0, asked for as [major].[minor]. */
    fun versionRefusalLine(status: Int, major: Int, minor: Int): String =
        "Handshake: Android Auto answered the version request $major.$minor with status $status (not 0): it did not accept the version. " +
            "The handshake goes on, and what fails next (the TLS step) comes from this."

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
