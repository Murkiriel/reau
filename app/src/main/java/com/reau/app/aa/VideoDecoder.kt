// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/VideoDecoder.kt, commit 0abbe2a.
// Changes: package; the stall watchdog's rule is DecoderStallWatch. As copied, the first input after a pause of over
// 3 s restarted the decoder (the wait was measured from the last picture), a codec that stopped taking input was
// never restarted (only input the codec accepted counted as input), and neither was one stuck before its first
// picture (the watchdog began with the first picture). The test for a hardware codec by name, for Android below 10,
// is cut (this app starts at Android 10). [onDecoded] is told each picture's time from input to output.
// Adapted from headunit-revived (AGPLv3): decoder/VideoDecoder.kt
// Trimmed to the H.264 (video/avc) hardware/software MediaCodec path only. HUR's H.265/HEVC,
// bundled FFmpeg, software-YUV GL sink, and Settings dependencies are removed — OpenCfMoto
// requests only H.264 in service discovery and renders straight to a caller-supplied Surface
// (the encoder input surface of VideoPipeline).
package com.reau.app.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import com.reau.app.util.BoundedRelease
import com.reau.app.util.CodecErrors
import com.reau.app.util.CodecMetrics
import com.reau.app.util.CodecNames
import com.reau.app.util.CodecReport
import java.nio.ByteBuffer
import java.util.Locale

interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

class VideoDecoder {
    companion object {
        private const val TIMEOUT_US = 10000L

        /** How long the output thread waits in the codec for a picture. The call returns the moment one is ready, so
         *  this costs no latency; it only sets how often an idle decoder (Waze stopped) wakes: 10 times a second
         *  instead of 100. The stall check runs once per wake and needs seconds (DecoderStallWatch.STALL_MS). */
        private const val OUTPUT_POLL_US = 100_000L
        private const val OUTPUT_JOIN_MS = 500L

        /** The phone's H.264 decoders as the look at the codec list that picked one saw them ([CodecReport.candidates]), for the `codecs:` line. */
        @Volatile private var candidateReport: String? = null

        /** The key the H.264 decoder's name is remembered under ([CodecNames]); a test pre-seeds it to pick the software decoder. */
        internal const val AVC_DECODER_KEY = "decoder video/avc hardware"

        /** Why the decoder is restarted for giving no picture while input flows. */
        private const val STALL_RESTART = "sync_stall"

        /** Output exceptions in a row after which the decoder is restarted. */
        private const val RESTART_AFTER_ERRORS = 3

        /**
         * Whether the first NAL unit found in the first 32 bytes of [data] (from [offset], [size] bytes) is an SPS or a PPS (types 7 and 8).
         * (A picture too short for a start code and a NAL header has no place to look at: the range is empty.)
         */
        internal fun isCodecConfigData(data: ByteArray, offset: Int, size: Int): Boolean {
            for (i in offset until (offset + size - 4).coerceAtMost(offset + 32)) {
                val header = nalHeaderAfterStartCode(data, i)
                if (header >= 0) return header < offset + size && data[header].toInt() and 0x1F in SPS_AND_PPS
            }
            return false
        }

        /** Where the NAL header is when a start code (00 00 00 01, or 00 00 01) begins at [i], or -1 when none does. */
        private fun nalHeaderAfterStartCode(data: ByteArray, i: Int): Int = when {
            data[i].toInt() != 0 || data[i + 1].toInt() != 0 -> -1
            data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
            data[i + 2].toInt() == 1 -> i + 3
            else -> -1
        }

        private val SPS_AND_PPS = 7..8

        /** Chips whose decoders need every parameter-set buffer flagged as codec config (by the name of the codec). */
        internal fun alwaysFlagsConfig(codecName: String?): Boolean {
            val name = codecName?.lowercase(Locale.ROOT) ?: return false
            return name.contains(".rk.") || name.contains("allwinner") || name.contains(".tcc.")
        }

        /** Whether an input buffer goes to the codec flagged as codec config: it holds an SPS/PPS and the codec wants it, or has none yet. */
        internal fun flagsAsConfig(isConfig: Boolean, alwaysFlags: Boolean, configured: Boolean): Boolean = isConfig && (alwaysFlags || !configured)

        /** A picture that does not fit the codec's input buffer is dropped whole (and a keyframe asked for), never cut. */
        internal fun tooBig(remaining: Int, capacity: Int): Boolean = remaining > capacity

        /** The pictures a second, from [frames] counted in [elapsedMs]. */
        internal fun fpsOf(frames: Int, elapsedMs: Long): Int = (frames * 1000 / elapsedMs).toInt()

        /** Whether the fps count of the window is due: a second passed. */
        internal fun fpsWindowOver(elapsedMs: Long): Boolean = elapsedMs >= 1000

        /** Whether a codec that started on a picture with [dropped] pictures dropped before it, [startsOnKeyframe] or not, begins mid-chain. */
        internal fun startedMidChain(dropped: Int, startsOnKeyframe: Boolean): Boolean = dropped > 0 && !startsOnKeyframe

        /** The output thread asks for a restart from the third exception in a row. */
        internal fun restartAfterErrors(consecutive: Int): Boolean = consecutive >= RESTART_AFTER_ERRORS
    }

    private var codec: MediaCodec? = null
    private var codecBufferInfo: MediaCodec.BufferInfo? = null
    private var mSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var running = false
    private var startTime = 0L

    private var mWidth = 0
    private var mHeight = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codecConfigured = false
    private var currentCodecName: String? = null

    // The codec the `codecs:` line of the ride log was told for, so a restart on the same one does not tell it again.
    private var reportedCodec: String? = null

    /** Fallback dimensions used if SPS parsing fails (set to the negotiated AA resolution). */
    @Volatile var fallbackWidth = 0
    @Volatile var fallbackHeight = 0

    var dimensionsListener: VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    @Volatile var onFirstFrameListener: (() -> Unit)? = null
    @Volatile var lastFrameRenderedMs: Long = 0L

    @Volatile private var decoderNeedsRestart = false
    @Volatile private var decoderRestartReason: String? = null
    /** Tells a real decoder stall (input flowing, no output) from Android Auto simply pausing video
     *  (no input) during a UI transition or call. */
    private val stallWatch = DecoderStallWatch()

    /** How long the compositor this decoder draws into has been stuck inside one frame (0: not stuck), asked at each check of the stall watch. */
    @Volatile var downstreamBusyMs: () -> Long = { 0L }

    /** Spaces out the starts after a failed one, and asks for the software decoder when the hardware one keeps failing. */
    private val startGuard = DecoderStartGuard()

    /** Invoked when the decoder must be re-primed — the transport requests a fresh keyframe. */
    var onDecoderError: (() -> Unit)? = null

    /**
     * Invoked, with the reason, when a picture was lost for good in here without the decoder being restarted: the chain of
     * pictures is broken and the next ones predict from what went missing, so the session asks for a keyframe (otherwise the
     * picture stays broken until Android Auto's own, a minute away).
     */
    @Volatile var onFrameLost: ((String) -> Unit)? = null

    /** How many pictures came while there was no codec (starting up, waiting out a failed start, no surface) since the last one started. */
    private var droppedWhileDown = 0

    /** Each picture out, with the microseconds since its input went in. */
    @Volatile var onDecoded: ((Long) -> Unit)? = null

    /** Whether a codec is started and being drained (false while one starts up, after a stop, and while the guard waits out a failed start). */
    val isRunning: Boolean get() = running

    val videoWidth: Int get() = mWidth
    val videoHeight: Int get() = mHeight

    private fun handleOutputFormatChange(format: MediaFormat) {
        AaLog.i("Output Format Changed: $format")
        val newWidth = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { mWidth }
        val newHeight = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { mHeight }
        if (mWidth != newWidth || mHeight != newHeight) {
            AaLog.i("Video dimensions changed via format: ${newWidth}x$newHeight")
            mWidth = newWidth
            mHeight = newHeight
            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        }
        try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (_: Exception) {}
    }

    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (mSurface === surface) return
            AaLog.i("New surface set: $surface")
            if (codec != null) stop("New surface")
            startGuard.reset()
            mSurface = surface
            lastFrameRenderedMs = 0L
        }
    }

    @Suppress("TooGenericExceptionCaught") // MediaCodec.release can throw anything; logged
    fun stop(reason: String = "unknown") {
        synchronized(this) {
            running = false
            outputThread?.takeIf { it != Thread.currentThread() }?.let { thread ->
                thread.interrupt()
                joinOutput(thread)
            }
            outputThread = null

            // release() alone, and with a time limit: a codec whose driver hangs must not take the session's thread with it. stop() first
            // is not needed to release a codec that runs, and it was the call that hung in the ridescreen-aa fork.
            codec?.let { held ->
                // The framework's own count of this decoder's life, once, before it goes (bounded: it costs 300 ms at most, and the release follows).
                CodecMetrics.logBounded("decoder", currentCodecName, held) { AaLog.i(it) }
                BoundedRelease.run(
                    task = { try { held.release() } catch (e: Exception) { AaLog.e("Error releasing decoder", e) } },
                    joinMs = BoundedRelease.RELEASE_JOIN_MS,
                    onStuck = {
                        AaLog.w("Decoder release did not return in ${BoundedRelease.RELEASE_JOIN_MS} ms: given up on, the session goes on")
                        com.reau.app.data.ProjectionSummary.current.watchdog("decoder-release-stuck")
                    },
                )
            }

            codec = null
            codecBufferInfo = null
            codecConfigured = false
            if (!reason.startsWith("restart")) {
                sps = null
                pps = null
                mWidth = 0
                mHeight = 0
            }
            lastFrameRenderedMs = 0L
            AaLog.i("Decoder stopped: $reason")
        }
    }

    /**
     * Waits for the output thread (at most [OUTPUT_JOIN_MS]) so that [MediaCodec.stop] never meets a dequeue still
     * pending. The caller may itself have been interrupted (the receiver interrupts its session thread at Desligar,
     * and that thread ends here): a plain join would throw at once and skip the wait, so the wait goes on and the
     * interrupt is given back at the end.
     */
    private fun joinOutput(thread: Thread) {
        var interrupted = false
        val deadline = SystemClock.elapsedRealtime() + OUTPUT_JOIN_MS
        while (thread.isAlive) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            try {
                thread.join(left)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun scheduleRestart(reason: String) {
        decoderRestartReason = reason
        decoderNeedsRestart = true
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int) {
        synchronized(this) {
            stallWatch.onInput(SystemClock.elapsedRealtime())
            if (decoderNeedsRestart) {
                AaLog.w("Decoder restart requested: $decoderRestartReason")
                com.reau.app.data.ProjectionSummary.current.watchdog("decoder") // counted in the projection's summary
                if (decoderRestartReason == STALL_RESTART && startGuard.stalled()) {
                    AaLog.w("Decoder stalled twice: the software decoder for the rest of the session")
                    com.reau.app.data.ProjectionSummary.current.watchdog("decoder-software")
                }
                stop("restart: $decoderRestartReason")
                decoderNeedsRestart = false
                decoderRestartReason = null
                onDecoderError?.invoke()
            }

            if (codec == null) {
                startWith(buffer, offset, size)
                if (codec == null) {
                    droppedWhileDown++
                    return
                }
                tellIfStartedMidChain(buffer, offset, size)
            }

            val buf = ByteBuffer.wrap(buffer, offset, size)
            while (buf.hasRemaining()) {
                if (!feedInputBuffer(buf)) return
            }
        }
    }

    /**
     * A codec just started on this picture. If pictures were dropped before it and this one is not a keyframe, the codec starts in the
     * middle of a chain and shows green or black until the next keyframe: ask for one.
     */
    private fun tellIfStartedMidChain(buffer: ByteArray, offset: Int, size: Int) {
        val dropped = droppedWhileDown
        droppedWhileDown = 0
        if (startedMidChain(dropped, AaStats.hasIdr(buffer, offset, size))) onFrameLost?.invoke("$dropped picture(s) dropped before the decoder started")
    }

    /** No decoder yet: reads the stream's parameters from this picture and starts one when the surface, the size and the wait after a failure allow. */
    private fun startWith(buffer: ByteArray, offset: Int, size: Int) {
        if (!codecConfigured) {
            scanAndApplyConfig(buffer, offset, size)
            if (mWidth == 0 && fallbackWidth > 0 && fallbackHeight > 0) {
                AaLog.i("Fallback to negotiated dimensions: ${fallbackWidth}x$fallbackHeight")
                mWidth = fallbackWidth
                mHeight = fallbackHeight
                dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
            }
        }
        val sized = mWidth != 0 && mHeight != 0
        if (mSurface?.isValid == true && sized && startGuard.mayTry(SystemClock.elapsedRealtime())) start(mWidth, mHeight)
    }

    @Suppress("TooGenericExceptionCaught") // a bad SPS is logged and ignored
    private fun scanAndApplyConfig(buffer: ByteArray, offset: Int, size: Int) {
        val empty = NalSplitter.forEachNalUnit(buffer, offset, size) { nalData, headerLen ->
            val nalFirstByte = nalData[headerLen].toInt()
            val nalType = nalFirstByte and 0x1F
            if (nalType == 7) { // SPS
                sps = nalData
                try {
                    val offsetInNal = if (sps!![2].toInt() == 1) 3 else 4
                    SpsParser.parse(sps!!, offsetInNal, sps!!.size - offsetInNal)?.let {
                        if (mWidth != it.width || mHeight != it.height) {
                            AaLog.i("H.264 SPS parsed: ${it.width}x${it.height}")
                            mWidth = it.width; mHeight = it.height
                            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                        }
                    }
                } catch (e: Exception) { AaLog.e("Failed to parse SPS data", e) }
            } else if (nalType == 8) pps = nalData
            if (sps != null) codecConfigured = true
        }
        if (empty > 0 && !emptyNalLogged) {
            emptyNalLogged = true
            AaLog.w("H.264: a NAL unit with no bytes after its start code was skipped (said once)")
        }
    }

    /** Said once: an empty NAL unit is skipped every time, and one a frame would fill the log. */
    private var emptyNalLogged = false

    @Suppress("TooGenericExceptionCaught") // any codec failure is logged and leaves the decoder stopped
    private fun start(width: Int, height: Int) {
        try {
            startTime = System.nanoTime()
            val mimeType = "video/avc"
            val bestCodec = (
                if (startGuard.useSoftware) {
                    findBestCodec(mimeType, false, width, height)
                } else {
                    CodecNames.pick(AVC_DECODER_KEY) { findBestCodec(mimeType, true, width, height) }
                }
                ) ?: error("No decoder available for $mimeType")
            this.currentCodecName = bestCodec

            codec = MediaCodec.createByCodecName(bestCodec)
            codecBufferInfo = MediaCodec.BufferInfo()

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            // Hands each picture out without holding frames back for reordering (the stream has none): the joystick's answer comes sooner.
            val lowLatency = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && startGuard.lowLatency
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

            check(mSurface!!.isValid) { "Surface not valid" }

            AaLog.i("Configuring decoder: $bestCodec for ${width}x${height} (low latency: $lowLatency)")
            codec?.configure(format, mSurface, null, 0)
            try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (_: Exception) {}
            codec?.start()
            stallWatch.reset()

            running = true
            startGuard.worked()
            outputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                outputThreadLoop()
            }.apply { name = "AaVideoDecoder-Output"; start() }

            AaLog.i("Codec initialized: $bestCodec")
            reportChoice(bestCodec, width, height)
        } catch (e: Exception) {
            startGuard.failed(SystemClock.elapsedRealtime())
            AaLog.e("Failed to start decoder (${startGuard.failuresInARow} in a row; next try in ${DecoderStartGuard.backoffMs(startGuard.failuresInARow)} ms" +
                "${if (startGuard.useSoftware) ", software decoder next" else ""}): ${CodecErrors.describe(e, currentCodecName)}", e)
            try { codec?.release() } catch (_: Exception) {}
            codec = null; running = false
        }
    }

    private fun shouldAlwaysFlagConfig(): Boolean = alwaysFlagsConfig(currentCodecName)

    @Suppress("TooGenericExceptionCaught") // any codec failure is logged; the frame is dropped
    private fun feedInputBuffer(buffer: ByteBuffer): Boolean {
        val currentCodec = codec ?: return false
        try {
            var inputIndex = -1
            var attempts = 0
            while (attempts < 30) {
                inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) break
                attempts++
            }
            if (inputIndex < 0) {
                AaLog.e("Input buffer feed failed (full)")
                onFrameLost?.invoke("no input buffer free in the decoder")
                return false
            }

            val inputBuffer = currentCodec.getInputBuffer(inputIndex) ?: return false
            inputBuffer.clear()
            val capacity = inputBuffer.capacity()

            val isConfig = buffer.hasArray() && isCodecConfigData(buffer.array(), buffer.position(), buffer.remaining())
            val flags = if (flagsAsConfig(isConfig, shouldAlwaysFlagConfig(), codecConfigured)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else 0

            if (tooBig(buffer.remaining(), capacity)) {
                // Cut, it would reach the codec as a broken picture and the rest as another (A-15): the whole unit goes, the buffer
                // goes back empty, and a keyframe is asked for, as AapVideo does with any other loss.
                AaLog.w("Frame too large: ${buffer.remaining()} > $capacity, dropped; asking for a keyframe")
                buffer.position(buffer.limit())
                currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                onDecoderError?.invoke()
                return false
            }
            inputBuffer.put(buffer)

            inputBuffer.flip()
            val pts = (System.nanoTime() - startTime) / 1000
            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, flags)
            return true
        } catch (e: Exception) {
            AaLog.e("Error feeding input buffer (${CodecErrors.describe(e, currentCodecName)})", e)
            onFrameLost?.invoke("the decoder failed on a picture")
            return false
        }
    }

    // Whether the wait now going on was already told as excused (the output thread's only).
    private var waitExcusedLogged = false

    /**
     * Whether the stall watch says to restart the decoder now. A stalled decoder whose compositor is the one stuck inside a frame is
     * not restarted (it would not help): that is told once per wait, in the ride log.
     */
    private fun stallRestartDue(now: Long): Boolean {
        val busyMs = downstreamBusyMs()
        if (stallWatch.restartDue(now, busyMs)) return true
        if (!stallWatch.isStalled(now)) {
            waitExcusedLogged = false
        } else if (!waitExcusedLogged) {
            waitExcusedLogged = true
            com.reau.app.data.RideDiagnostics.warn(
                "aa",
                "decoder wait not counted as a stall: the compositor has been inside one frame for ${busyMs}ms",
            )
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught") // a codec failure is counted and restarts the decoder
    private fun outputThreadLoop() {
        AaLog.i("Output thread started")
        var consecutiveErrors = 0

        while (running) {
            val currentCodec = codec
            val bufferInfo = codecBufferInfo
            if (currentCodec == null || bufferInfo == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }
            try {
                val outputIndex = currentCodec.dequeueOutputBuffer(bufferInfo, OUTPUT_POLL_US)
                if (outputIndex >= 0) {
                    currentCodec.releaseOutputBuffer(outputIndex, true) // render=true → to Surface
                    onDecoded?.invoke((System.nanoTime() - startTime) / 1000 - bufferInfo.presentationTimeUs)
                    lastFrameRenderedMs = SystemClock.elapsedRealtime()
                    // Debug builds only can say no (FaultInjection.DECODER_STALL): the watch then sees a decoder that gives nothing out.
                    if (!com.reau.app.debug.FaultInjection.swallowDecoderOutput()) stallWatch.onOutput()
                    consecutiveErrors = 0
                    onFirstFrameListener?.let { it(); onFirstFrameListener = null }

                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsLogTime
                    if (fpsWindowOver(elapsed)) {
                        if (lastFpsLogTime != 0L) onFpsChanged?.invoke(fpsOf(frameCount, elapsed))
                        frameCount = 0
                        lastFpsLogTime = now
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChange(currentCodec.outputFormat)
                }

                // Real stall = we're actively feeding input but getting no output → restart.
                // If no input is arriving, Android Auto has just paused video (UI transition, call,
                // decoder recovery) — stay idle and let it resume; the compositor keep-alive holds
                // the bike connection meanwhile. This avoids tearing down a healthy decoder and
                // fighting AA's own Media Stop/Start sequence.
                val now = SystemClock.elapsedRealtime()
                if (stallRestartDue(now)) {
                    AaLog.w(
                        "Decoder stall detected (no output for ${stallWatch.waitingMs(now)}ms, " +
                            "input ${stallWatch.inputGapMs(now)}ms ago). Forcing restart.",
                    )
                    com.reau.app.debug.FaultInjection.decoderRestarted()
                    scheduleRestart(STALL_RESTART)
                    break
                }
            } catch (e: Exception) {
                if (running) {
                    consecutiveErrors++
                    AaLog.w("Codec exception in output thread (attempt $consecutiveErrors): ${CodecErrors.describe(e, currentCodecName)}")
                    if (restartAfterErrors(consecutiveErrors)) {
                        AaLog.e("Too many consecutive exceptions in output thread. Forcing restart.")
                        scheduleRestart("sync_consecutive_errors")
                        break
                    }
                    try { Thread.sleep(50) } catch (ignore: Exception) {}
                }
            }
        }
        AaLog.i("Output thread stopped")
    }

    private fun findBestCodec(mimeType: String, preferHardware: Boolean, width: Int, height: Int): String? {
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        val infos = codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        candidateReport = CodecReport.candidates(infos.map { candidateOf(it, mimeType, width, height) })
        val hw = infos.find { isHardwareAccelerated(it) }
        val sw = infos.find { !isHardwareAccelerated(it) }
        val selected = if (preferHardware && hw != null) hw.name else sw?.name ?: hw?.name
        AaLog.i("findBestCodec: hw=${hw?.name}, sw=${sw?.name}, preferHardware=$preferHardware, selected=$selected")
        return selected
    }

    /** What the codec's capabilities say about the stream's size and Baseline, for the `codecs:` line (null: could not be read). */
    private fun candidateOf(info: MediaCodecInfo, mimeType: String, width: Int, height: Int): CodecReport.Candidate {
        val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull()
        return CodecReport.Candidate(
            name = info.name,
            hardware = isHardwareAccelerated(info),
            sizeSupported = runCatching { caps?.videoCapabilities?.isSizeSupported(width, height) }.getOrNull(),
            baselineSupported = caps?.profileLevels?.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline },
        )
    }

    /**
     * The `codecs:` line of the ride log: the decoders the phone lists (from the one look at the list that picked this one), and the one in use;
     * told once for this decoder, and again only if the choice changed (the software decoder after failures). Never throws.
     */
    private fun reportChoice(chosen: String, width: Int, height: Int) {
        if (chosen == reportedCodec) return
        reportedCodec = chosen
        runCatching {
            val hardware = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == chosen }?.let { isHardwareAccelerated(it) }
            AaLog.i(CodecReport.line("decoder", "video/avc", "${width}x$height", candidateReport ?: "not listed", chosen, hardware))
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return info.isHardwareAccelerated
    }
}

/** Os bits do SPS acabaram antes de um campo que o leitor precisa: o SPS veio vazio ou cortado. */
private class SpsEndOfData : RuntimeException("SPS sem bits suficientes")

private class BitReader(private val buffer: ByteArray, private val offset: Int, private val size: Int) {
    private var bitPosition = offset * 8
    private val bitLimit = (offset + size) * 8

    /** Le um bit; depois do fim dos dados lanca [SpsEndOfData] (antes devolvia 0 e inventava um tamanho). */
    fun readBit(): Int {
        if (bitPosition >= bitLimit) throw SpsEndOfData()
        return (buffer[bitPosition / 8].toInt() shr (7 - (bitPosition++ % 8))) and 1
    }

    fun readBits(count: Int): Int {
        var res = 0
        repeat(count) { res = (res shl 1) or readBit() }
        return res
    }

    /** ue(v): Exp-Golomb sem sinal. Mais de 30 zeros seguidos nao e um SPS (e estouraria o Int). */
    fun readUE(): Int {
        var zeros = 0
        while (readBit() == 0) {
            zeros++
            if (zeros > MAX_EXP_GOLOMB_ZEROS) throw SpsEndOfData()
        }
        return if (zeros == 0) 0 else (1 shl zeros) - 1 + readBits(zeros)
    }

    /** se(v): Exp-Golomb com sinal (H.264 9.1.1): codeNum k impar vale (k+1)/2, k par vale -k/2. */
    fun readSE(): Int {
        val codeNum = readUE()
        return if (codeNum % 2 == 1) (codeNum + 1) / 2 else -(codeNum / 2)
    }

    private companion object {
        const val MAX_EXP_GOLOMB_ZEROS = 30
    }
}

data class SpsData(val width: Int, val height: Int)

internal object SpsParser {
    private val HIGH_PROFILES = listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)
    private const val MAX_REF_FRAMES_IN_POC_CYCLE = 255

    /** O tamanho do video no SPS, ou nulo se o SPS estiver vazio, cortado antes do tamanho ou malformado. */
    fun parse(sps: ByteArray, offset: Int, size: Int): SpsData? {
        try {
            val reader = BitReader(sps, offset, size)
            reader.readBits(8)
            val profileIdc = reader.readBits(8)
            reader.readBits(16)
            reader.readUE()
            if (profileIdc in HIGH_PROFILES) skipHighProfileFields(reader)
            reader.readUE() // log2_max_frame_num_minus4
            if (!skipPicOrderCount(reader)) return null
            reader.readUE(); reader.readBit() // max_num_ref_frames, gaps_in_frame_num_allowed
            val w = (reader.readUE() + 1) * 16
            val hMap = reader.readUE()
            val mbs = reader.readBit()
            val h = (2 - mbs) * (hMap + 1) * 16
            if (mbs == 0) reader.readBit()
            reader.readBit()
            if (reader.readBit() == 1) {
                val l = reader.readUE(); val r = reader.readUE()
                val t = reader.readUE(); val b = reader.readUE()
                return SpsData(w - (l + r) * 2, h - (t + b) * 2)
            }
            return SpsData(w, h)
        } catch (_: Exception) { return null }
    }

    /** Campos so dos perfis altos: croma, profundidade de bits e as listas de escala. */
    private fun skipHighProfileFields(reader: BitReader) {
        val chroma = reader.readUE()
        if (chroma == 3) reader.readBit() // separate_colour_plane_flag
        reader.readUE(); reader.readUE(); reader.readBit() // profundidades de luma e croma, transformacao sem perdas
        if (reader.readBit() == 1) {
            repeat(if (chroma != 3) 8 else 12) { skipScalingList(reader, if (it < 6) 16 else 64) }
        }
    }

    /**
     * scaling_list() (H.264 7.3.2.1.1.1) de [size] valores (16 para 4x4, 64 para 8x8) se a lista estiver presente. Os deltas
     * sao se(v); quando o proximo valor chega a 0 a lista acaba (0 na primeira posicao e "usar a matriz padrao").
     */
    private fun skipScalingList(reader: BitReader, size: Int) {
        if (reader.readBit() == 0) return // seq_scaling_list_present_flag
        var last = 8
        repeat(size) {
            val next = Math.floorMod(last + reader.readSE(), 256) // nextScale = (lastScale + delta_scale + 256) % 256
            if (next == 0) return // fim da lista (ou matriz padrao, se for o 1o valor): os valores seguintes repetem o ultimo
            last = next
        }
    }

    /** pic_order_cnt_type e o que vem junto (7.3.2.1.1). Devolve false se o ciclo do tipo 1 for maior que a especificacao permite. */
    private fun skipPicOrderCount(reader: BitReader): Boolean {
        when (reader.readUE()) {
            0 -> reader.readUE() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                reader.readBit() // delta_pic_order_always_zero_flag
                reader.readSE() // offset_for_non_ref_pic
                reader.readSE() // offset_for_top_to_bottom_field
                val cycle = reader.readUE() // num_ref_frames_in_pic_order_cnt_cycle
                if (cycle > MAX_REF_FRAMES_IN_POC_CYCLE) return false
                repeat(cycle) { reader.readSE() } // offset_for_ref_frame[i]
            }
        }
        return true
    }
}
