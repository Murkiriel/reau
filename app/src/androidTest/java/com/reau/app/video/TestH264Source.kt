package com.reau.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Test helper: the video a fake Android Auto sends. A MediaCodec encoder draws [TestPattern] (800×480) and hands
 * back H.264 as Android Auto would send it: one buffer with SPS and PPS, then one buffer per frame.
 */
object TestH264Source {

    /** One buffer of the encoder's output (Annex-B). */
    class Unit(val data: ByteArray, val isConfig: Boolean, val isKey: Boolean)

    /** Encodes [frames] frames of the pattern; the first is a keyframe and one more comes every [keyframeEvery]. */
    fun encode(frames: Int, keyframeEvery: Int = 30): List<Unit> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, TestPattern.WIDTH, TestPattern.HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 100) // keyframes are asked for explicitly below
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val units = mutableListOf<Unit>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val info = MediaCodec.BufferInfo()
            var out = 0
            for (frame in 0 until frames) {
                if (frame > 0 && frame % keyframeEvery == 0) {
                    codec.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
                TestPattern.drawOn(surface, hardware = true)
                // One picture in, one out: the encoder's input queue never has to drop a picture.
                val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
                while (out <= frame && System.currentTimeMillis() < deadline) out += drain(codec, info, units)
            }
            codec.signalEndOfInputStream() // whatever the encoder still holds comes out now
            val deadline = System.currentTimeMillis() + END_WAIT_MS
            while (out < frames && System.currentTimeMillis() < deadline) out += drain(codec, info, units)
            surface.release()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        check(units.count { !it.isConfig } == frames) { "the encoder gave ${units.count { !it.isConfig }} frames for $frames" }
        return units
    }

    /** Takes what the encoder has ready; returns how many frames (not the SPS/PPS buffer) came out. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, into: MutableList<Unit>): Int {
        val index = codec.dequeueOutputBuffer(info, 20_000)
        if (index < 0) return 0
        val data = ByteArray(info.size).also { codec.getOutputBuffer(index)?.get(it) }
        codec.releaseOutputBuffer(index, false)
        if (data.isEmpty()) return 0
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        into += Unit(data, isConfig, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
        return if (isConfig) 0 else 1
    }

    private const val FRAME_WAIT_MS = 500L
    private const val END_WAIT_MS = 3_000L
}
