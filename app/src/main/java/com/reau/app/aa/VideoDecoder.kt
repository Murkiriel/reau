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
import com.reau.app.util.CodecNames
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

        /** The key the H.264 decoder's name is remembered under ([CodecNames]); a test pre-seeds it to pick the software decoder. */
        internal const val AVC_DECODER_KEY = "decoder video/avc hardware"
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

    /** Invoked when the decoder must be re-primed — the transport requests a fresh keyframe. */
    var onDecoderError: (() -> Unit)? = null

    /** Each picture out, with the microseconds since its input went in. */
    @Volatile var onDecoded: ((Long) -> Unit)? = null

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

            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (e: Exception) { AaLog.e("Error releasing decoder", e) }

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
                stop("restart: $decoderRestartReason")
                decoderNeedsRestart = false
                decoderRestartReason = null
                onDecoderError?.invoke()
            }

            if (codec == null) {
                if (!codecConfigured) {
                    scanAndApplyConfig(buffer, offset, size)
                    if (mWidth == 0 && fallbackWidth > 0 && fallbackHeight > 0) {
                        AaLog.i("Fallback to negotiated dimensions: ${fallbackWidth}x$fallbackHeight")
                        mWidth = fallbackWidth
                        mHeight = fallbackHeight
                        dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                    }
                }

                if (mSurface == null || !mSurface!!.isValid) return
                if (mWidth == 0 || mHeight == 0) return

                start(mWidth, mHeight)
            }

            if (codec == null) return

            val buf = ByteBuffer.wrap(buffer, offset, size)
            while (buf.hasRemaining()) {
                if (!feedInputBuffer(buf)) return
            }
        }
    }

    private fun forEachNalUnit(buffer: ByteArray, offset: Int, size: Int, callback: (ByteArray, Int) -> Unit) {
        var currentPos = offset
        val limit = offset + size

        while (currentPos < limit - 3) {
            var nalStart = -1
            var startCodeLen = 0
            for (i in currentPos until limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0) {
                    if (buffer[i + 2].toInt() == 0 && buffer[i + 3].toInt() == 1) { nalStart = i; startCodeLen = 4; break }
                    else if (buffer[i + 2].toInt() == 1) { nalStart = i; startCodeLen = 3; break }
                }
            }
            if (nalStart != -1) {
                var nalEnd = limit
                for (j in (nalStart + startCodeLen) until limit - 3) {
                    if (buffer[j].toInt() == 0 && buffer[j + 1].toInt() == 0 &&
                        (buffer[j + 2].toInt() == 1 || (buffer[j + 2].toInt() == 0 && buffer[j + 3].toInt() == 1))) {
                        nalEnd = j; break
                    }
                }
                val rawNal = buffer.copyOfRange(nalStart, nalEnd)
                val fixedNal = if (startCodeLen == 3) {
                    ByteArray(rawNal.size + 1).apply { this[0] = 0; System.arraycopy(rawNal, 0, this, 1, rawNal.size) }
                } else rawNal
                callback(fixedNal, 4)
                currentPos = nalEnd
            } else break
        }
    }

    @Suppress("TooGenericExceptionCaught") // a bad SPS is logged and ignored
    private fun scanAndApplyConfig(buffer: ByteArray, offset: Int, size: Int) {
        forEachNalUnit(buffer, offset, size) { nalData, headerLen ->
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
    }

    @Suppress("TooGenericExceptionCaught") // any codec failure is logged and leaves the decoder stopped
    private fun start(width: Int, height: Int) {
        try {
            startTime = System.nanoTime()
            val mimeType = "video/avc"
            val bestCodec = CodecNames.pick(AVC_DECODER_KEY) { findBestCodec(mimeType, true) }
                ?: error("No decoder available for $mimeType")
            this.currentCodecName = bestCodec

            codec = MediaCodec.createByCodecName(bestCodec)
            codecBufferInfo = MediaCodec.BufferInfo()

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)

            check(mSurface!!.isValid) { "Surface not valid" }

            AaLog.i("Configuring decoder: $bestCodec for ${width}x${height}")
            codec?.configure(format, mSurface, null, 0)
            try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (_: Exception) {}
            codec?.start()
            stallWatch.reset()

            running = true
            outputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                outputThreadLoop()
            }.apply { name = "AaVideoDecoder-Output"; start() }

            AaLog.i("Codec initialized: $bestCodec")
        } catch (e: Exception) {
            AaLog.e("Failed to start decoder", e)
            codec = null; running = false
        }
    }

    private fun shouldAlwaysFlagConfig(): Boolean {
        val name = currentCodecName?.lowercase(Locale.ROOT) ?: return false
        return name.contains(".rk.") || name.contains("allwinner") || name.contains(".tcc.")
    }

    private fun isCodecConfigData(data: ByteArray, offset: Int, size: Int): Boolean {
        if (size < 5) return false
        for (i in offset until (offset + size - 4).coerceAtMost(offset + 32)) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val headerPos: Int
                if (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) headerPos = i + 4
                else if (data[i + 2].toInt() == 1) headerPos = i + 3
                else continue
                if (headerPos >= offset + size) return false
                val b = data[headerPos].toInt()
                val nalType = b and 0x1F
                return nalType == 7 || nalType == 8
            }
        }
        return false
    }

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
                return false
            }

            val inputBuffer = currentCodec.getInputBuffer(inputIndex) ?: return false
            inputBuffer.clear()
            val capacity = inputBuffer.capacity()

            val isConfig = buffer.hasArray() && isCodecConfigData(buffer.array(), buffer.position(), buffer.remaining())
            val flags = if (isConfig && (shouldAlwaysFlagConfig() || !codecConfigured)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else 0

            if (buffer.remaining() <= capacity) {
                inputBuffer.put(buffer)
            } else {
                AaLog.w("Frame too large: ${buffer.remaining()} > $capacity. Truncating!")
                val limit = buffer.limit()
                buffer.limit(buffer.position() + capacity)
                inputBuffer.put(buffer)
                buffer.limit(limit)
            }

            inputBuffer.flip()
            val pts = (System.nanoTime() - startTime) / 1000
            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, flags)
            return true
        } catch (e: Exception) {
            AaLog.e("Error feeding input buffer", e)
            return false
        }
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
                    stallWatch.onOutput()
                    consecutiveErrors = 0
                    onFirstFrameListener?.let { it(); onFirstFrameListener = null }

                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsLogTime
                    if (elapsed >= 1000) {
                        if (lastFpsLogTime != 0L) onFpsChanged?.invoke((frameCount * 1000 / elapsed).toInt())
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
                if (stallWatch.isStalled(now)) {
                    AaLog.w(
                        "Decoder stall detected (no output for ${stallWatch.waitingMs(now)}ms, " +
                            "input ${stallWatch.inputGapMs(now)}ms ago). Forcing restart.",
                    )
                    scheduleRestart("sync_stall")
                    break
                }
            } catch (e: Exception) {
                if (running) {
                    consecutiveErrors++
                    AaLog.w("Codec exception in output thread (attempt $consecutiveErrors): ${e.message}")
                    if (consecutiveErrors >= 3) {
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

    private fun findBestCodec(mimeType: String, preferHardware: Boolean): String? {
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        val infos = codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val hw = infos.find { isHardwareAccelerated(it) }
        val sw = infos.find { !isHardwareAccelerated(it) }
        val selected = if (preferHardware && hw != null) hw.name else sw?.name ?: hw?.name
        AaLog.i("findBestCodec: hw=${hw?.name}, sw=${sw?.name}, preferHardware=$preferHardware, selected=$selected")
        return selected
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
