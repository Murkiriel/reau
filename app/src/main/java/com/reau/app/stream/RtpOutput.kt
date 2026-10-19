package com.reau.app.stream

import com.reau.app.dash.video.NalProcessor
import com.reau.app.dash.video.RtpPacketizer

/**
 * The way out of the encoder, for one stream to the dash:
 *
 *     encoder buffer ──▶ KeyframeGate ──▶ NalProcessor ──▶ RtpPacketizer ──▶ [sendRtp]
 *
 * One per stream: a new stream gets a new RTP sequence and source id, and starts with its gate closed. It hears one
 * encoder at a time ([switchTo]); what another one still hands over is ignored, so an encoder swap never mixes two
 * encoders' frames. No Android in here: it runs in a plain JVM test with hand-made buffers.
 *
 * [tap], if given, is handed every unit after it was sent, with whether it is a keyframe and its frame's time: that
 * is where the debug recording ([H264Recorder]) listens.
 *
 * Every method may be called from any thread; the encoders' output threads call [onEncoded].
 */
internal class RtpOutput(
    private val tap: ((ByteArray, Boolean, Long) -> Unit)? = null,
    private val sendRtp: (ByteArray) -> Unit,
) {

    /**
     * Frames (not parameter sets) and their bytes that went on to the dash, and the keyframes among them (to
     * measure the burst a keyframe is): how many, and the biggest one's bytes and RTP packets.
     */
    data class Counts(val frames: Int, val bytes: Long, val keyframes: Int = 0, val keyBytesMax: Int = 0, val keyPacketsMax: Int = 0)

    private val gate = KeyframeGate()

    // RTP packets made: a keyframe's own count is the difference across its NALs.
    private var packetsMade = 0

    private val packetizer = RtpPacketizer { packet ->
        packetsMade++
        sendRtp(packet)
    }

    // All NALs of one frame share the time that frame was stamped with, however late the encoder hands it back.
    private var framePtsMs = 0L
    private val nalProcessor = NalProcessor { nal, isKey ->
        packetizer.packetize(nal, endOfAU = true, wallClockMs = framePtsMs)
        // Only what really went out: a send that throws never gets here.
        tap?.invoke(nal, isKey, framePtsMs)
    }

    private var encoder: Any? = null
    private var frames = 0
    private var bytes = 0L
    private var keyframes = 0
    private var keyBytesMax = 0
    private var keyPacketsMax = 0

    /** Frames held back before a keyframe, so far. */
    val heldBack: Int @Synchronized get() = gate.dropped

    /** Keyframes not sent because no SPS/PPS had come before them. */
    val idrWithheld: Int @Synchronized get() = nalProcessor.withheldIdr

    /** From now on only [encoder]'s buffers are heard, starting at its next keyframe. */
    @Synchronized
    fun switchTo(encoder: Any) {
        this.encoder = encoder
        gate.close()
    }

    /** The dash is at the start of a stream again: nothing more goes out until a keyframe. */
    @Synchronized
    fun holdUntilKeyframe() {
        gate.close()
    }

    /** One buffer of [from]'s output (Annex-B), with the time its frame was stamped with. */
    @Synchronized
    fun onEncoded(from: Any, annexB: ByteArray, isKey: Boolean, ptsMs: Long) {
        if (from !== encoder || !gate.admit(annexB, isKey)) return
        framePtsMs = ptsMs
        val withheldBefore = nalProcessor.withheldIdr
        val packetsBefore = packetsMade
        nalProcessor.process(annexB)
        if (nalProcessor.withheldIdr != withheldBefore) {
            // The keyframe stayed back, so what follows it has nothing to refer to: wait for the next one.
            gate.close()
            return
        }
        if (!KeyframeGate.isParameterSetsOnly(annexB, isKey)) {
            frames++
            bytes += annexB.size
            if (isKey) {
                keyframes++
                keyBytesMax = maxOf(keyBytesMax, annexB.size)
                keyPacketsMax = maxOf(keyPacketsMax, packetsMade - packetsBefore)
            }
        }
    }

    /** Totals since the previous call, then starts counting again. */
    @Synchronized
    fun takeCounts(): Counts = Counts(frames, bytes, keyframes, keyBytesMax, keyPacketsMax).also {
        frames = 0
        bytes = 0L
        keyframes = 0
        keyBytesMax = 0
        keyPacketsMax = 0
    }
}
