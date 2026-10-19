package com.reau.app.dash.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.reau.app.dash.BitrateMode
import com.reau.app.util.CodecNames

/**
 * MediaCodec H.264 encoder for the dash stream:
 *   526 × 300, Baseline L4.1, 1-second IDR interval.
 *
 * Cadence/bitrate are DRIVEN BY THE FRAME LOOP, not fixed here (the stream package's pacing). The
 * dash shows a map well at only **2–4 fps / 100–200 kbps**. A map doesn't need video frame rates, and
 * crucially a few fps NEVER overruns the dash decoder — which is the real cause of the blink/stutter seen
 * at 24 fps. So the encoder is configured at the active rate (4 fps hint, ~200 kbps) and the bitrate is
 * dropped live via [requestBitrate] when the map goes static. ([FPS] here is
 * only the encoder's frame-rate HINT; the real rate is the surface feed rate.)
 *
 * History (kept so we don't re-walk it): we'd climbed 4 → 12 → 24 fps and 0.8 → 1.2 Mbps chasing
 * "smoother", but measurement shows the dash deliberately stays LOW. Reverting to that envelope is the fix.
 *
 * Nothing is drawn here. The compositor draws with OpenGL on [inputSurface] and stamps each
 * frame with `eglPresentationTimeANDROID`; that time comes back in the codec's `BufferInfo` and is what [onFrame]
 * gets. A Surface takes one kind of producer, so there is no Canvas path, and no pull mode either:
 * [EncoderOutputPump]'s thread hands over every buffer the moment the codec has it.
 *
 * Why the thread: a frame loop that stops until the frame it has just submitted comes back loses ~17 ms of every
 * frame, and in use the phone topped out near 15.5 fps that way. Whenever the codec happened to fall one frame
 * behind, the wait vanished and the same run reached 22 fps, so the loop's capacity depended on chance.
 *
 * @param onFrame  called on the output thread with (annexBBytes, isKeyFrame, ptsMs) for each output buffer, ptsMs
 *   being the presentation time the frame was posted to [inputSurface] with.
 */
class DashEncoder(
    private val onFrame: (ByteArray, Boolean, Long) -> Unit,
) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        // Encoder frame-rate HINT only (actual rate = how fast the loop feeds the surface). The loop paces
        // 4 fps (active) / 2 fps (idle).
        const val FPS     = 4
        // ~200 kbps while the map moves. The loop drops this to ~100 kbps via requestBitrate() when the
        // map is static.
        // Far under the old 1.2 Mbps that risked overrunning the dash decoder.
        const val BITRATE = 200_000
        const val BITRATE_IDLE = 100_000
        /** Safety net for a compositor that stops: with nothing posted to the input surface for this long, the
         *  codec encodes the last frame again by itself. OpenCfMoto uses the same 900 ms. What keeps the dash fed
         *  when Android Auto pauses is the compositor's own clock, not this. */
        const val REPEAT_AFTER_US = 900_000L
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TAG = "DashEncoder"

        /** Realtime encoding at exactly the stream's own rate: priority 0 (realtime), the operating
         *  rate pinned to [fps] so the chip isn't clocked above it, and — Android 11+ — latency 1 so
         *  the encoder hands each frame back instead of holding it. Ride logs at 12 fps:
         *  ~25% of frames left the encoder a loop late, glued to the next one. */
        internal fun lowLatencyKeys(sdkInt: Int, fps: Int): Map<String, Int> = buildMap {
            put(MediaFormat.KEY_PRIORITY, 0)
            put(MediaFormat.KEY_OPERATING_RATE, fps)
            if (sdkInt >= android.os.Build.VERSION_CODES.R) put(MediaFormat.KEY_LATENCY, 1)
        }

        /**
         * The `KEY_BITRATE_MODE` for the rider's [mode], as the encoder offers it: [offered] says whether it offers
         * a mode, null when that cannot be read (then it is asked anyway, and a refusal falls back when the codec is
         * configured). A mode not offered falls to the other one, then to the codec's own default (no key), and
         * [RateMode.label] says so for the log.
         */
        internal fun rateMode(mode: BitrateMode?, offered: (Int) -> Boolean?): RateMode {
            mode ?: return RateMode(null, "default")
            val wanted = constantOf(mode)
            if (offered(wanted) != false) return RateMode(wanted, mode.name)
            val other = BitrateMode.entries.first { it != mode }
            return if (offered(constantOf(other)) != false) {
                RateMode(constantOf(other), "${other.name} (${mode.name} not offered)")
            } else {
                RateMode(null, "default (${mode.name} not offered)")
            }
        }

        private fun constantOf(mode: BitrateMode): Int = when (mode) {
            BitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            BitrateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
    }

    /** A `KEY_BITRATE_MODE` value to set (null: none, the codec's default) and how the log names it. */
    data class RateMode(val constant: Int?, val label: String)

    private var codec: MediaCodec? = null
    /** Where the compositor draws: the codec's input surface. Null before [prepare] and after [release]. */
    var inputSurface: Surface? = null
        private set
    @Volatile private var lastPtsMs = 0L
    private val pending = PendingFrames()
    private var outputPump: EncoderOutputPump? = null

    /** Set on a failed [prepare] — the caller logs this to RideDiagnostics (the on-disk ride log
     * pulled after the fact) since a live logcat isn't available once a rider is out on the road. */
    var lastPrepareError: String? = null
        private set

    /**
     * Bring up the encoder. Returns false (and leaves the encoder unset) if the device has no
     * usable AVC encoder or MediaCodec otherwise rejects this config — e.g. no hardware encoder
     * compatible with Baseline L4.1 + COLOR_FormatSurface, or the codec already claimed by
     * another app (screen recorder, camera app). Never throws.
     *
     * [fpsHint] defaults to [FPS] (today's exact value) — additive
     * (`DashConfig.streamFps`), to test rates other than the proven-stable 4fps envelope this
     * class's own header doc describes at length. Still only a HINT to the codec's own bitrate/
     * rate-control math, same caveat as [FPS] itself always had — the real rate is however often
     * the compositor actually posts frames to [inputSurface].
     *
     * [bitrateBps] defaults to [BITRATE] (today's exact value) — additive as well:
     * the caller passes [com.reau.app.stream.DashLogic.effectiveActiveBitrate],
     * the rider's ceiling raised as needed so [fpsHint] never gets less than
     * [com.reau.app.stream.DashLogic.MIN_BITS_PER_FRAME] per frame.
     *
     * The profile is always Baseline: the dash's decoder rejects Main/High (seen on a real dash;
     * better-dash documents the same), so it isn't a parameter.
     * [iFrameIntervalSec] defaults to `1`. [bitrateMode] null leaves `KEY_BITRATE_MODE` unset (the
     * codec's own default); otherwise [rateMode] sets it, and if the codec refuses to be
     * configured with it, it is tried once more without it (`bitrateMode=fallback` in the log).
     *
     * It also sets `KEY_REPEAT_PREVIOUS_FRAME_AFTER` ([REPEAT_AFTER_US]).
     */
    fun prepare(
        fpsHint: Int = FPS,
        bitrateBps: Int = BITRATE,
        iFrameIntervalSec: Int = 1,
        bitrateMode: BitrateMode? = null,
    ): Boolean {
        fun buildFormat(lowLatency: Boolean, rate: Int? = null) = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fpsHint)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            if (lowLatency) lowLatencyKeys(android.os.Build.VERSION.SDK_INT, fpsHint).forEach { (k, v) -> setInteger(k, v) }
            if (rate != null) setInteger(MediaFormat.KEY_BITRATE_MODE, rate)
        }
        // Prefer an explicit HARDWARE AVC encoder — a software fallback runs the
        // CPU hot, which is exactly what this whole project exists to avoid.
        val name = buildFormat(lowLatency = false).let { f -> CodecNames.pick("encoder $f") { selectHardwareEncoder(f) } }
        val rate = rateMode(bitrateMode) { mode -> modeOffered(name, mode) }
        val detail = "fpsHint=$fpsHint bitrateBps=$bitrateBps profile=baseline " +
            "iFrameIntervalSec=$iFrameIntervalSec repeatAfterUs=$REPEAT_AFTER_US bitrateMode=${rate.label}"
        // A vendor encoder that rejects the realtime keys outright must not cost the rider the
        // stream: retry once without them, then once more without the bitrate mode.
        return runCatching { startCodec(name, buildFormat(lowLatency = true, rate.constant), "$detail lowLatency=on") }
            .recoverCatching { e ->
                startCodec(name, buildFormat(lowLatency = false, rate.constant), "$detail lowLatency=fallback (${e.javaClass.simpleName})")
            }.recoverCatching { e ->
                if (rate.constant == null) throw e
                startCodec(name, buildFormat(lowLatency = false), "$detail lowLatency=fallback bitrateMode=fallback (${e.javaClass.simpleName})")
            }.onFailure { e ->
            lastPrepareError = "${e.javaClass.simpleName}: ${e.message} (tried ${name ?: "OS-default encoder"})"
            Log.e(TAG, "prepare() failed: $lastPrepareError")
        }.onSuccess { c ->
            lastPrepareError = null
            codec = c
        }.isSuccess
    }

    /** Creates, configures and starts one codec instance; releases it again if any step throws. */
    private fun startCodec(name: String?, format: MediaFormat, detail: String): MediaCodec {
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MIME)
        return runCatching {
            Log.i(TAG, "Encoder: ${c.name} $detail")
            com.reau.app.data.RideDiagnostics.log("stream", "encoder: ${c.name} $detail")
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
            outputPump = EncoderOutputPump(c) { idx, info -> emitOutput(c, idx, info) }.also { it.start() }
            c
        }.onFailure {
            runCatching { c.release() }
            inputSurface?.release(); inputSurface = null
        }.getOrThrow()
    }

    /** Whether the encoder [name] offers the bitrate [mode]; null when that cannot be read (no name, no capabilities). */
    private fun modeOffered(name: String?, mode: Int): Boolean? = runCatching {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name } ?: return null
        info.getCapabilitiesForType(MIME).encoderCapabilities?.isBitrateModeSupported(mode)
    }.getOrNull()

    /** Find a hardware AVC encoder that supports this format; null → let the OS pick. */
    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // First try true hardware-accelerated encoders (API 29+ flag; minSdk is 29).
        val hardware = list.codecInfos.firstOrNull { info ->
            val avcEncoder = info.isEncoder && info.supportedTypes.any { it.equals(MIME, true) }
            avcEncoder && info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        if (hardware != null) return hardware.name
        // Fallback: the OS-preferred encoder for this format.
        return runCatching { list.findEncoderForFormat(format) }.getOrNull()
    }

    /**
     * For the frame loop, once per frame: throws what went wrong on the output
     * thread since the last call (see [EncoderOutputPump.check]); otherwise returns the frames that
     * came out, how many of them left late and the time they spent in the encoder, since then.
     * Only frames announced with [frameSubmitted] are counted.
     */
    internal fun collectOutput(): PendingFrames.Stats {
        outputPump?.check()
        return pending.takeStats()
    }

    /**
     * For the compositor, right before it posts a frame to [inputSurface] (`eglSwapBuffers`): starts the clock
     * that measures how long that frame spends in the encoder ([collectOutput]). Only the statistics need it:
     * the frame's time reaches [onFrame] through the codec either way.
     */
    fun frameSubmitted(ptsMs: Long) = pending.submitted(ptsMs, System.nanoTime())

    /** Hands one output buffer to [onFrame] and releases it; true if it was a real frame
     *  (not the SPS/PPS config buffer). */
    private fun emitOutput(codec: MediaCodec, idx: Int, info: MediaCodec.BufferInfo): Boolean {
        val buf = codec.getOutputBuffer(idx)
        // Pass EVERY buffer through, including CODEC_CONFIG (SPS/PPS) — the NAL processor needs
        // the parameter sets to bundle them with each IDR, otherwise the dash can't initialise its
        // decoder and times out.
        val isFrame = buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
        try {
            if (buf != null && info.size > 0) {
                val data = ByteArray(info.size).also { buf.get(it) }
                // The time is the one the frame was posted to the input surface with, as the codec gives it back.
                // The SPS/PPS buffer is no frame: it takes no entry and carries the last frame's time.
                if (isFrame) {
                    pending.emitted(System.nanoTime())
                    lastPtsMs = info.presentationTimeUs / 1000
                }
                onFrame(data, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, lastPtsMs)
            }
        } finally {
            codec.releaseOutputBuffer(idx, false)
        }
        return isFrame
    }

    /**
     * Change the target bitrate live, without rebuilding the encoder — the stream switches between the
     * ~200 kbps (moving) and ~100 kbps (static) profiles this way. MediaCodec applies
     * PARAMETER_KEY_VIDEO_BITRATE to the running session; no IDR or reconfigure needed.
     */
    fun requestBitrate(bps: Int) {
        val c = codec ?: return
        runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            })
        }.onFailure {
            Log.w(TAG, "requestBitrate($bps) failed: ${it.message}")
            com.reau.app.data.RideDiagnostics.warn("stream", "requestBitrate($bps) failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * Asks the ALREADY-RUNNING encoder for an extra sync (IDR) frame, without touching fps or
     * bitrate — used while the map is still (`DashLogic.idleSyncNeeded`). Same `setParameters`
     * mechanism [requestBitrate] already uses for a live parameter change; no reconfigure or restart.
     */
    fun requestSyncFrame() {
        val c = codec ?: return
        runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure {
            Log.w(TAG, "requestSyncFrame() failed: ${it.message}")
            com.reau.app.data.RideDiagnostics.warn("stream", "requestSyncFrame() failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun release() {
        // Output thread first, so nothing of this encoder is still being sent once release()
        // returns (the frame loop starts feeding the next encoder right after).
        outputPump?.stop()
        outputPump = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
