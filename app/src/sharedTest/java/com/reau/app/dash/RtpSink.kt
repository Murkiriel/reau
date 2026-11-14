package com.reau.app.dash

import java.io.File

/**
 * The receiving end of the dash's video, for tests: it takes the RTP packets as they arrive, checks each against the rules of the stream
 * (RFC 3550 and RFC 6184, as far as the dash's stream uses them) and puts the frames back together as an Annex-B stream that any H.264
 * player opens ([writeTo]). It decodes nothing. What breaks a rule is kept in [faults], one line each, and the stream goes on.
 *
 * The rules: version 2 and one payload type; the sequence number goes up by one, wrapping at 65536; one source id for the whole stream;
 * every packet of a frame carries the frame's timestamp, and a new frame a different one; the marker bit is on the last packet of a frame
 * and on no other; a NAL too big for one packet comes as FU-A pieces (type 28) with the start bit on the first, the end bit on the last and
 * the same NAL type on all; no aggregation packets (the dash refuses them); no packet over [maxPayload] bytes after the RTP header.
 */
class RtpSink(private val maxPayload: Int = MAX_PAYLOAD) {
    /** What broke a rule, in arrival order. */
    val faults = mutableListOf<String>()

    /** The frames put back together, each as Annex-B (a start code before each NAL unit), in arrival order. */
    val frames = mutableListOf<ByteArray>()

    /** The RTP timestamp of each frame of [frames]. */
    val timestamps = mutableListOf<Long>()

    var packets = 0
        private set

    private var lastSeq = -1
    private var ssrc: Long? = null
    private var payloadType = -1
    private var frame = ByteArray(0)
    private var frameTs: Long? = null
    private var lastFrameTs: Long? = null
    private var inPieces = false
    private var piecesType = -1

    @Synchronized
    fun onPacket(packet: ByteArray) {
        packets++
        val n = packets
        if (packet.size < HEADER + 1) {
            faults += "packet $n: ${packet.size} bytes, too short for an RTP header and a payload"
            return
        }
        if ((packet[0].toInt() and 0xC0) != 0x80) faults += "packet $n: RTP version ${(packet[0].toInt() and 0xC0) shr 6}, not 2"
        val marker = packet[1].toInt() and 0x80 != 0
        val pt = packet[1].toInt() and 0x7F
        if (payloadType < 0) payloadType = pt else if (pt != payloadType) faults += "packet $n: payload type $pt, the stream began with $payloadType"
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (lastSeq >= 0 && seq != (lastSeq + 1) and 0xFFFF) faults += "packet $n: sequence $seq after $lastSeq"
        lastSeq = seq
        val ts = word(packet, 4)
        val source = word(packet, 8)
        if (ssrc == null) ssrc = source else if (source != ssrc) faults += "packet $n: source id changed in the middle of the stream"
        if (packet.size - HEADER > maxPayload) faults += "packet $n: a payload of ${packet.size - HEADER} bytes, over $maxPayload"

        if (frameTs == null) {
            frameTs = ts
            if (ts == lastFrameTs) faults += "packet $n: a new frame with the timestamp of the one before"
        } else if (ts != frameTs) {
            faults += "packet $n: timestamp $ts inside the frame of timestamp $frameTs (a frame ended without its marker?)"
        }

        val type = packet[HEADER].toInt() and 0x1F
        when {
            type == FU_A -> piece(packet, n)
            type in AGGREGATION -> faults += "packet $n: an aggregation packet (type $type), which the dash refuses"
            type == 0 || type > FU_A -> faults += "packet $n: NAL type $type is not one the stream may carry"
            else -> {
                if (inPieces) faults += "packet $n: a whole NAL in the middle of a NAL that came in pieces"
                inPieces = false
                frame += START_CODE + packet.copyOfRange(HEADER, packet.size)
            }
        }

        if (marker) {
            if (inPieces) faults += "packet $n: the marker on a piece that is not the last of its NAL"
            inPieces = false
            frames += frame
            timestamps += ts
            frame = ByteArray(0)
            lastFrameTs = frameTs
            frameTs = null
        }
    }

    private fun piece(packet: ByteArray, n: Int) {
        if (packet.size < HEADER + 3) {
            faults += "packet $n: an FU-A piece with no bytes of the NAL"
            return
        }
        val fu = packet[HEADER + 1].toInt()
        val start = fu and 0x80 != 0
        val end = fu and 0x40 != 0
        val nalType = fu and 0x1F
        if (fu and 0x20 != 0) faults += "packet $n: the reserved bit of the FU header is set"
        if (start && end) faults += "packet $n: an FU-A piece that is both the first and the last"
        if (start) {
            if (inPieces) faults += "packet $n: a first piece before the last piece of the NAL before"
            inPieces = true
            piecesType = nalType
            frame += START_CODE + ((packet[HEADER].toInt() and 0xE0) or nalType).toByte()
        } else {
            if (!inPieces) faults += "packet $n: a piece of a NAL whose first piece did not come"
            else if (nalType != piecesType) faults += "packet $n: a piece of NAL type $nalType inside a NAL of type $piecesType"
        }
        frame += packet.copyOfRange(HEADER + 2, packet.size)
        if (end) inPieces = false
    }

    /** Whether the stream ended in the middle of a frame (packets came after the last marker). */
    val endedInsideAFrame: Boolean @Synchronized get() = frameTs != null

    /** The whole stream so far, Annex-B: every complete frame, in order. */
    @Synchronized
    fun annexB(): ByteArray = frames.fold(ByteArray(0)) { all, f -> all + f }

    /** Writes [annexB] to [file] (a `.h264` a player opens) and gives the number of bytes written. */
    fun writeTo(file: File): Int = annexB().also { file.writeBytes(it) }.size

    private fun word(b: ByteArray, at: Int): Long = (0..3).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xFF) }

    companion object {
        /** The most the app puts after the RTP header: the packetizer's own limit. */
        const val MAX_PAYLOAD = 1380
        private const val HEADER = 12
        private const val FU_A = 28
        private val AGGREGATION = 24..27
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
