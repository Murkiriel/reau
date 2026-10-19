package com.reau.app.web

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.reau.app.dash.video.DashEncoder
import com.reau.app.dash.video.EncoderOutputPump
import com.reau.app.util.CodecNames

/**
 * The web viewers' encoder: H.264 Baseline, [WIDTH]×[HEIGHT], 30 fps, about 1.5 Mbps constant, a key frame every
 * second, the parameter sets repeated in each key frame (so a viewer can start at any of them), and the last picture
 * repeated every 500 ms while Android Auto draws nothing new. Hardware preferred. It is the dash encoder's twin, with
 * the same realtime keys and the same fallbacks, but its own size and rate; it never touches the dash's.
 *
 * [onOutput] is called on the encoder's output thread with each buffer: its bytes (Annex-B), whether it is a key frame and
 * whether it is the codec's parameter-sets buffer (not a frame).
 *
 * Only compiled by the build: what the hardware does with it can only be proved on a device.
 */
internal class WebEncoder(private val onOutput: (ByteArray, Boolean, Boolean) -> Unit) {
    companion object {
        const val WIDTH = 800
        const val HEIGHT = 480
        const val FPS = 30
        const val BITRATE = 1_500_000
        private const val KEY_INTERVAL_SEC = 1
        private const val REPEAT_AFTER_US = 500_000L
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var codec: MediaCodec? = null
    private var pump: EncoderOutputPump? = null

    /** Where the compositor draws: the codec's input surface. Null before [prepare] and after [release]. */
    var inputSurface: Surface? = null
        private set

    /** Why the last [prepare] failed, for the log (never anything about the viewers). */
    var lastError: String? = null
        private set

    /** Brings the encoder up; false if the device has no usable AVC encoder or the codec refuses the format. Never throws. */
    fun prepare(): Boolean {
        val name = CodecNames.pick("web encoder ${WIDTH}x$HEIGHT") { selectHardwareEncoder() }
        return runCatching { start(name, lowLatency = true, constantRate = true) }
            .recoverCatching { start(name, lowLatency = false, constantRate = true) }
            .recoverCatching { start(name, lowLatency = false, constantRate = false) }
            .onFailure { lastError = "${it.javaClass.simpleName}: ${it.message} (tried ${name ?: "the OS default encoder"})" }
            .onSuccess { lastError = null }
            .isSuccess
    }

    private fun format(lowLatency: Boolean, constantRate: Boolean) = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_INTERVAL_SEC)
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
        if (constantRate) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        if (lowLatency) DashEncoder.lowLatencyKeys(android.os.Build.VERSION.SDK_INT, FPS).forEach { (k, v) -> setInteger(k, v) }
    }

    private fun start(name: String?, lowLatency: Boolean, constantRate: Boolean): MediaCodec {
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MIME)
        return runCatching {
            c.configure(format(lowLatency, constantRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
            pump = EncoderOutputPump(c) { idx, info -> emit(c, idx, info) }.also { it.start() }
            codec = c
            c
        }.onFailure {
            runCatching { c.release() }
            inputSurface?.release()
            inputSurface = null
        }.getOrThrow()
    }

    private fun emit(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        val buffer = c.getOutputBuffer(idx)
        try {
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size).also { buffer.get(it) }
                onOutput(
                    bytes,
                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0,
                )
            }
        } finally {
            c.releaseOutputBuffer(idx, false)
        }
    }

    /** Asks the running encoder for a key frame (no reconfiguration), so a new viewer's decoder starts soon. */
    fun requestSyncFrame() {
        val c = codec ?: return
        runCatching { c.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        pump?.stop()
        pump = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    /** A hardware AVC encoder that offers the format; null lets the OS choose. */
    private fun selectHardwareEncoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hardware = list.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } && info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        return hardware?.name ?: runCatching { list.findEncoderForFormat(format(lowLatency = false, constantRate = false)) }.getOrNull()
    }
}
