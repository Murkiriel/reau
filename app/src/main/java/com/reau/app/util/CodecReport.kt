package com.reau.app.util

import java.util.Locale

/**
 * The lines the ride log keeps about the phone's H.264 codecs (diagnostics only; nothing here changes which codec is used or how):
 *
 * - `codecs: encoder video/avc 526x300 [c2.qti.avc.encoder(hw size=ok baseline=ok) c2.android.avc.encoder(sw size=ok baseline=ok)] chosen=c2.qti.avc.encoder (hw)`
 *   once per session for the decoder and once for the encoder: the candidates the phone lists, whether each takes the size and Baseline
 *   (`?`: its capabilities could not be read), and the one that was used. Where a third-party phone fails, the other candidates are
 *   what the log did not say before.
 * - `encoder accepted: …` / `encoder output format: …`: what the encoder took of the format the app asked for ([accepted]). The first
 *   joins the codec's input format (read once it is started) with its output format (read when the codec announces it, before the first
 *   buffer); the second is the output format alone.
 *
 * Pure text; the lists are read from [android.media.MediaCodecList] only when a session starts, never on the hot path.
 */
object CodecReport {
    /** One codec of the phone's list, with what its capabilities say about the stream's size and Baseline (null: could not be read). */
    data class Candidate(val name: String, val hardware: Boolean, val sizeSupported: Boolean?, val baselineSupported: Boolean?)

    /** `c2.qti.avc.encoder(hw size=ok baseline=ok) c2.android.avc.encoder(sw size=ok baseline=no)`, or `none` with no candidate. */
    fun candidates(list: List<Candidate>): String =
        if (list.isEmpty()) "none" else list.joinToString(" ") { c -> "${c.name}(${kind(c.hardware)} ${capabilities(c)})" }

    private fun capabilities(c: Candidate) = "size=${ok(c.sizeSupported)} baseline=${ok(c.baselineSupported)}"

    /** The `codecs:` line of one [role] (`decoder` or `encoder`) for a stream of [size] (`526x300`); [candidates] is [candidates]'s text. */
    @Suppress("LongParameterList") // the facts of one line of the ride log
    fun line(role: String, mime: String, size: String, candidates: String, chosen: String?, chosenHardware: Boolean?): String =
        "codecs: $role $mime $size [$candidates] chosen=${chosen ?: "-"}" + (chosenHardware?.let { " (${kind(it)})" } ?: "")

    /**
     * What the codec took of the format asked for, in the order of [KEYS]: `bitrate-mode=CBR profile=Baseline level=4096 bitrate=200000
     * frame-rate=4 priority=0`. Only a key the codec gave is shown. A key that was [asked] and the codec did not give at all is no
     * difference but silence (the c2 encoders do not echo `latency` or `i-frame-interval`): it goes in `; not echoed: latency,
     * i-frame-interval`. A key given with another value than the one asked ends the text: `; differs: bitrate asked 400000 got 200000`.
     * With nothing given the text is `nothing echoed`.
     */
    fun accepted(asked: Map<String, Number>, got: (String) -> Number?): String {
        val values = KEYS.map { key -> key to got(key) }
        val silent = values.filter { (key, value) -> value == null && key in asked }.map { it.first }
        val differs = values.mapNotNull { (key, value) ->
            val wanted = asked[key]
            if (wanted == null || value == null || value.toDouble() == wanted.toDouble()) null else "$key asked ${number(wanted)} got ${number(value)}"
        }
        val given = values.mapNotNull { (key, value) -> value?.let { "$key=${named(key, it)}" } }
        return (if (given.isEmpty()) "nothing echoed" else given.joinToString(" ")) +
            (if (silent.isEmpty()) "" else "; not echoed: ${silent.joinToString(", ")}") +
            (if (differs.isEmpty()) "" else "; differs: ${differs.joinToString(", ")}")
    }

    /** The value of a key from [first] when it has one, else from [then]: the codec's output format over its input one. */
    fun overlay(first: (String) -> Number?, then: (String) -> Number?): (String) -> Number? = { key -> first(key) ?: then(key) }

    /** The keys read from a format (the names of `MediaFormat.KEY_*`; written out so the JVM tests need no Android). */
    val KEYS = listOf("latency", "bitrate-mode", "profile", "level", "i-frame-interval", "bitrate", "frame-rate", "priority", "operating-rate")

    private fun named(key: String, value: Number): String = when (key) {
        "bitrate-mode" -> BITRATE_MODES[value.toInt()] ?: number(value)
        "profile" -> AVC_PROFILES[value.toInt()] ?: number(value)
        else -> number(value)
    }

    private fun number(value: Number): String =
        if (value is Float || value is Double) String.format(Locale.US, "%.1f", value.toDouble()).removeSuffix(".0") else value.toString()

    private fun kind(hardware: Boolean) = if (hardware) "hw" else "sw"

    private fun ok(value: Boolean?) = when (value) {
        true -> "ok"
        false -> "no"
        null -> "?"
    }

    // EncoderCapabilities.BITRATE_MODE_*; MediaCodecInfo.CodecProfileLevel.AVCProfile*.
    private val BITRATE_MODES = mapOf(0 to "CQ", 1 to "VBR", 2 to "CBR", 3 to "CBR_FD")
    private val AVC_PROFILES = mapOf(1 to "Baseline", 0x10000 to "ConstrainedBaseline", 2 to "Main", 4 to "Extended", 8 to "High")
}
