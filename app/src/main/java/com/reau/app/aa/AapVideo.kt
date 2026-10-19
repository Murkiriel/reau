// Rewritten for REau after two files of the same name:
// - OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapVideo.kt, commit 0abbe2a (itself adapted
//   from headunit-revived, AGPLv3: aap/AapVideo.kt). Kept: the reassembly buffer, the two payload offsets (10 after a
//   timestamp, 2 without one), the start-code test, the synchronous hand-over to the decoder, the 1 s spacing of
//   keyframe requests.
// - open-headunit (AGPL-3.0): app/src/main/java/com/andrerinas/openheadunit/aap/AapVideo.kt, commit 93c065dd. Kept:
//   the way it lets VideoFragmentAssembler decide and asks for a keyframe on every anomaly.
// Changes: the decoder is a function, so this class runs in a plain JVM test; the buffer size and the clock are
// parameters; open-headunit's frame pool, video thread and periodic anomaly report are not here.
// What goes through is counted in [AaStats] (units, pieces, frames rebuilt and lost).
package com.reau.app.aa

import java.nio.ByteBuffer

/**
 * Puts Android Auto's video-channel messages back together into H.264 access units and hands each to [decode].
 *
 * A message is whole (flags 11) or cut in pieces (9 first, 8 middle, 10 last). The payload of a whole message or of
 * a first piece starts with the two type bytes and, for picture data, an 8-byte timestamp: the H.264 begins at
 * offset 10, or at offset 2 for a codec configuration. The pieces after the first are plain continuation.
 *
 * [VideoFragmentAssembler] holds the ordering rules. Whenever the run of pieces breaks (a piece went missing, a
 * first piece has no start code, the frame outgrew the buffer), the broken frame never reaches the decoder and
 * [onFrameCorrupted] is called, at most once a second ([VideoRecoveryPolicy]): the transport then asks Android
 * Auto for a keyframe by cycling video focus.
 *
 * [process] returns whether the message was video. False leaves it to the control layer: the video channel also
 * carries its own setup, start, stop and focus messages, whole messages with no start code in them.
 */
internal class AapVideo(
    private val decode: (ByteArray, Int, Int) -> Unit,
    private val onFrameCorrupted: () -> Unit,
    bufferBytes: Int = DEFAULT_BUFFER_BYTES,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val stats: AaStats? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val assembler = VideoFragmentAssembler()
    private val messageBuffer = ByteBuffer.allocate(bufferBytes)

    // Far enough in the past for the first request to go out, whatever the clock starts at.
    private var lastKeyframeRequestMs = Long.MIN_VALUE / 2

    fun process(message: AapMessage): Boolean {
        val buf = message.data
        val len = message.size
        val flags = message.flags.toInt()
        if (flags in PIECE_FLAGS) stats?.piece()
        val wasCorrupt = assembler.isFrameCorrupt
        val decision = assembler.onMessage(
            flags,
            payloadStartsAt10 = hasPayloadAt(buf, len, VideoFragmentAssembler.OFFSET_TIMESTAMP_INDICATION),
            payloadStartsAt2 = hasPayloadAt(buf, len, VideoFragmentAssembler.OFFSET_MEDIA_INDICATION),
        )
        decision.anomaly?.let {
            // A run already known broken loses no second frame to each orphan piece behind it.
            if (it != VideoFragmentAssembler.Anomaly.ORPHANED_FRAGMENT || !wasCorrupt) stats?.lost()
            requestKeyframe("video stream broken ($it)")
        }

        return when (val action = decision.action) {
            is VideoFragmentAssembler.Action.DecodeWhole -> {
                count(buf, action.payloadOffset, len - action.payloadOffset)
                timedDecode(buf, action.payloadOffset, len - action.payloadOffset)
                true
            }
            is VideoFragmentAssembler.Action.BeginAssembly -> {
                messageBuffer.clear()
                append(buf, action.payloadOffset, len - action.payloadOffset)
                true
            }
            VideoFragmentAssembler.Action.Append -> {
                append(buf, 0, len)
                true
            }
            VideoFragmentAssembler.Action.AppendAndDecode -> {
                if (append(buf, 0, len)) {
                    count(messageBuffer.array(), 0, messageBuffer.position())
                    stats?.assembled()
                    timedDecode(messageBuffer.array(), 0, messageBuffer.position())
                }
                true
            }
            is VideoFragmentAssembler.Action.Discard -> action.consumed
        }
    }

    /** Forgets a frame in progress. For the end of a session. */
    fun release() = assembler.reset()

    /**
     * Adds to the frame being assembled; false (and the frame is given up) if it does not fit. The buffer is only
     * ever emptied by the first piece of the next frame: the assembler lets nothing else in before that.
     */
    private fun append(data: ByteArray, offset: Int, length: Int): Boolean {
        if (messageBuffer.remaining() >= length) {
            messageBuffer.put(data, offset, length)
            return true
        }
        stats?.lost()
        requestKeyframe("video stream broken (${assembler.onOverflow()})")
        return false
    }

    /** [decode], timed: this is the read thread, and it reads nothing else until the decoder takes the unit. */
    private fun timedDecode(buffer: ByteArray, offset: Int, length: Int) {
        val start = nanoTime()
        try {
            decode(buffer, offset, length)
        } finally {
            stats?.readHeld(nanoTime() - start)
        }
    }

    private fun count(buffer: ByteArray, offset: Int, length: Int) {
        stats?.unit(length, AaStats.hasIdr(buffer, offset, length))
    }

    /**
     * The decoder's queue was full and the frame went nowhere. Counted lost, and a keyframe asked for (at
     * most one a second, as for a broken frame): the frames after it predict from the one that went missing.
     */
    fun decoderBehind() {
        stats?.lost()
        requestKeyframe("the decoder is behind, a frame dropped (its queue of ${FrameQueue.CAPACITY} was full)")
    }

    private fun requestKeyframe(why: String) {
        val now = nowMs()
        if (!VideoRecoveryPolicy.canRequestKeyframe(now, lastKeyframeRequestMs)) return
        lastKeyframeRequestMs = now
        AaLog.w("AapVideo: $why, requesting a keyframe")
        onFrameCorrupted()
    }

    /** Whether a start code sits at [offset] of the message with something behind it. */
    private fun hasPayloadAt(buf: ByteArray, len: Int, offset: Int): Boolean {
        val startCode = startCodeLength(buf, offset)
        return startCode > 0 && len > offset + startCode
    }

    /** 3 or 4 for a 00 00 01 or 00 00 00 01 start code at [offset], -1 for none. */
    private fun startCodeLength(buf: ByteArray, offset: Int): Int {
        val zeros = offset + 2 < buf.size && buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0
        return when {
            !zeros -> -1
            buf[offset + 2].toInt() == 1 -> SHORT_START_CODE
            offset + LONG_START_CODE <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1 -> LONG_START_CODE
            else -> -1
        }
    }

    private companion object {
        // ~2 MB, as in OpenCfMoto: ample for an 800x480 H.264 access unit.
        const val DEFAULT_BUFFER_BYTES = Messages.DEF_BUFFER_LENGTH * 16
        const val SHORT_START_CODE = 3
        const val LONG_START_CODE = 4
        val PIECE_FLAGS = setOf(VideoFragmentAssembler.FLAG_FIRST, VideoFragmentAssembler.FLAG_MIDDLE, VideoFragmentAssembler.FLAG_LAST)
    }
}
