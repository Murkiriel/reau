package com.reau.app.web

/** The units of an Annex-B H.264 buffer: where they start, what they are, and whether a frame is a key one. */
object AnnexB {
    private const val SPS = 7
    private const val PPS = 8
    private const val IDR = 5

    /** The units of [data] without their start codes (3 or 4 bytes); what comes before the first start code is nothing. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = startCodes(data)
        return starts.mapIndexedNotNull { i, (_, payload) ->
            val end = starts.getOrNull(i + 1)?.first ?: data.size
            trimmed(data, payload, end).takeIf { it.isNotEmpty() }
        }
    }

    /** The NAL unit type: the low five bits of its first byte. */
    fun type(unit: ByteArray): Int = unit[0].toInt() and 0x1F

    /** Whether [data] holds an IDR slice, that is, a key frame. */
    fun hasIdr(data: ByteArray): Boolean = split(data).any { type(it) == IDR }

    /** Whether [data] holds an SPS or a PPS. */
    fun hasParameterSets(data: ByteArray): Boolean = split(data).any { type(it) == SPS || type(it) == PPS }

    /** Each start code as (where it begins, where its unit begins). */
    private fun startCodes(data: ByteArray): List<Pair<Int, Int>> {
        val found = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                found += i to i + 3
                i += 3
            } else {
                i++
            }
        }
        return found
    }

    /** The unit between [from] and [until], without the zero bytes that belong to the next 4-byte start code. */
    private fun trimmed(data: ByteArray, from: Int, until: Int): ByteArray {
        var end = until
        while (end > from && data[end - 1].toInt() == 0) end--
        return data.copyOfRange(from, end)
    }
}
