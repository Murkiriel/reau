package com.reau.app.dash.video

import android.media.MediaCodec
import android.media.MediaFormat
import com.reau.app.data.RideDiagnostics
import com.reau.app.util.CodecReport

/**
 * The lines of the ride log about the dash's encoder that [DashEncoder] tells once for a stream (`codecs: encoder …`, `encoder accepted: …`,
 * `encoder output format: …`; plan 23). Read from the codec once, when it starts and when it announces its output format; nothing here is on the frame path, nothing changes how the
 * encoder is built, and none of it throws.
 */
internal object EncoderReport {
    /** The `codecs:` line: the phone's AVC encoders as the one look at the codec list of the process saw them ([candidates]) and the one in use. */
    fun choice(c: MediaCodec, candidates: String?) {
        runCatching {
            val hardware = c.codecInfo.let { it.isHardwareAccelerated && !it.isSoftwareOnly }
            RideDiagnostics.log(
                "stream",
                CodecReport.line("encoder", MIME, "${DashEncoder.WIDTH}x${DashEncoder.HEIGHT} baseline", candidates ?: "not listed", c.name, hardware),
            )
        }
    }

    /** The input format the started codec holds (read once, right after `start()`; null when it cannot be read). The c2 encoders echo little in it. */
    fun inputFormat(c: MediaCodec): MediaFormat? = runCatching { c.inputFormat }.getOrNull()

    /**
     * What the codec took of the [asked] format: its [output] format when it announces it (before the first buffer, on the output thread,
     * never the frame loop's) over the [input] one read at the start. A key neither of them has is told as `not echoed`, not as a difference.
     */
    fun accepted(asked: MediaFormat, input: MediaFormat?, output: MediaFormat) {
        runCatching {
            val wanted = CodecReport.KEYS.mapNotNull { key -> numberOf(asked, key)?.let { key to it } }.toMap()
            val text = CodecReport.accepted(wanted, CodecReport.overlay({ numberOf(output, it) }, { key -> input?.let { numberOf(it, key) } }))
            RideDiagnostics.log("stream", "encoder accepted: $text")
        }
    }

    /** The codec announced its [output] format: the two lines about it (on the output thread). */
    fun outputAnnounced(asked: MediaFormat, input: MediaFormat?, output: MediaFormat) {
        outputFormat(output)
        accepted(asked, input, output)
        colour(asked, input, output)
    }

    /** With the experimental colour keys asked (and only then), what the codec says of them: its output format over its input one. */
    private fun colour(asked: MediaFormat, input: MediaFormat?, output: MediaFormat) {
        if (!asked.containsKey(MediaFormat.KEY_COLOR_STANDARD)) return
        runCatching {
            RideDiagnostics.log("stream", ColorMatrix.acceptedLine { key -> numberOf(output, key)?.toInt() ?: input?.let { numberOf(it, key)?.toInt() } })
        }
    }

    /** The codec's output format, when it changes (once, before the first buffer). */
    fun outputFormat(f: MediaFormat) {
        runCatching { RideDiagnostics.log("stream", "encoder output format: ${CodecReport.accepted(emptyMap()) { numberOf(f, it) }}") }
    }

    /** The number [key] holds in [format] (an int or a float; null when it has none). */
    private fun numberOf(format: MediaFormat, key: String): Number? = if (format.containsKey(key)) runCatching { format.getNumber(key) }.getOrNull() else null

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
}
