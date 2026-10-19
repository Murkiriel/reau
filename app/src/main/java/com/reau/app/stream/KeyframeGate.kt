package com.reau.app.stream

/**
 * What may reach the dash when a stream to it starts or restarts: nothing until a keyframe.
 *
 * A dash handed a partial frame first, with no SPS/PPS, shows black while video flows (OpenCfMoto's
 * 05-DEBUG-KNOWLEDGE). The gate starts closed; a keyframe opens it; [close]
 * shuts it again. Buffers that carry only parameter sets always pass: they are not shown, and the keyframe that
 * follows cannot be decoded without them.
 */
internal class KeyframeGate {
    /** Frames held back so far. */
    var dropped = 0
        private set

    private var open = false

    fun close() {
        open = false
    }

    /** Whether this buffer of the encoder's output (Annex-B) may go on to the dash. */
    fun admit(annexB: ByteArray, isKey: Boolean): Boolean {
        if (isKey) open = true
        val pass = open || isParameterSetsOnly(annexB, isKey)
        if (!pass) dropped++
        return pass
    }

    companion object {
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8

        /** The encoder's SPS/PPS buffer, as opposed to a coded picture: not a keyframe, and it starts with one. */
        fun isParameterSetsOnly(annexB: ByteArray, isKey: Boolean): Boolean =
            !isKey && firstNalType(annexB).let { it == NAL_SPS || it == NAL_PPS }

        /** Type of the NAL unit after the first start code (00 00 01 or 00 00 00 01), or -1. */
        private fun firstNalType(annexB: ByteArray): Int {
            var i = 0
            while (i < annexB.size && annexB[i].toInt() == 0) i++
            val startCode = i >= 2 && i + 1 < annexB.size && annexB[i].toInt() == 1
            return if (startCode) annexB[i + 1].toInt() and 0x1F else -1
        }
    }
}
