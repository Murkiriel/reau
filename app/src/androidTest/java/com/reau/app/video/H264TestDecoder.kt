package com.reau.app.video

import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Test helper: decodes the Annex-B H.264 the dash would receive back into pictures, so a test can look at what
 * actually went out. Software path (ByteBuffer output), no surface.
 */
class H264TestDecoder(private val width: Int = DashGlass.FRAME_W, private val height: Int = DashGlass.FRAME_H) {

    /** One decoded picture, kept as its three planes. */
    class Picture(
        val ptsMs: Long,
        val width: Int,
        val height: Int,
        private val y: ByteArray,
        private val u: ByteArray,
        private val v: ByteArray,
    ) {
        /** The colour at ([px], [py]), rows counted from the top (BT.601, video range). */
        fun colorAt(px: Int, py: Int): Int {
            val luma = (y[py * width + px].toInt() and 0xFF) - 16
            val cb = (u[(py / 2) * ((width + 1) / 2) + px / 2].toInt() and 0xFF) - 128
            val cr = (v[(py / 2) * ((width + 1) / 2) + px / 2].toInt() and 0xFF) - 128
            val r = (1.164 * luma + 1.596 * cr).toInt().coerceIn(0, 255)
            val g = (1.164 * luma - 0.813 * cr - 0.391 * cb).toInt().coerceIn(0, 255)
            val b = (1.164 * luma + 2.018 * cb).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }
    }

    /** One buffer of the encoder's output. */
    class Chunk(val data: ByteArray, val ptsMs: Long)

    /** Decodes [chunks] in order (the SPS/PPS buffer included, wherever it is) and returns every picture. */
    fun decode(chunks: List<Chunk>): List<Picture> {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val pictures = mutableListOf<Picture>()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(format, null, null, 0)
            codec.start()
            for (chunk in chunks) {
                feed(codec, chunk.data, chunk.ptsMs, flags = 0)
                drain(codec, pictures, endOfStream = false)
            }
            feed(codec, ByteArray(0), 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(codec, pictures, endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return pictures
    }

    private fun feed(codec: MediaCodec, data: ByteArray, ptsMs: Long, flags: Int) {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        check(index >= 0) { "the decoder took no input for ${TIMEOUT_US / 1000} ms" }
        checkNotNull(codec.getInputBuffer(index)).apply { clear(); put(data) }
        codec.queueInputBuffer(index, 0, data.size, ptsMs * 1000, flags)
    }

    private fun drain(codec: MediaCodec, into: MutableList<Picture>, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else POLL_US)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index < 0) continue
            if (info.size > 0) codec.getOutputImage(index)?.use { into += pictureOf(it, info.presentationTimeUs / 1000) }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    companion object {
        private const val TIMEOUT_US = 2_000_000L
        private const val POLL_US = 20_000L

        /** Copies the visible part of [image] (its crop rectangle) into tight planes. */
        fun pictureOf(image: Image, ptsMs: Long = 0): Picture {
            val crop = image.cropRect
            val w = crop.width()
            val h = crop.height()
            val chromaW = (w + 1) / 2
            val chromaH = (h + 1) / 2
            return Picture(
                ptsMs, w, h,
                plane(image.planes[0], crop.left, crop.top, w, h),
                plane(image.planes[1], crop.left / 2, crop.top / 2, chromaW, chromaH),
                plane(image.planes[2], crop.left / 2, crop.top / 2, chromaW, chromaH),
            )
        }

        private fun plane(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h)
            val buffer = plane.buffer
            for (row in 0 until h) {
                for (col in 0 until w) {
                    out[row * w + col] = buffer.get((top + row) * plane.rowStride + (left + col) * plane.pixelStride)
                }
            }
            return out
        }
    }
}
