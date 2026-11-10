package com.reau.app.util

import android.media.MediaCodec
import java.util.Locale

/**
 * `MediaCodec.getMetrics()` (API 26) of a codec, in one compact line, read once when the codec is let go. The framework counts on its
 * own side what the app counts on its (the codec's name and mime, the mode, the size, the error code and state, the latency of each
 * buffer, the frame rate), so the two can be set against each other, and the hardware or software choice is confirmed without `adb`.
 * The keys vary by vendor and Android version: the known ones come first, in a fixed order and without their long prefix, then those whose
 * name speaks of errors, drops, counts or frames (a few), and `+N keys` says how many more the bundle had.
 *
 * [read] never throws and, called through [BoundedRelease] (see [logBounded]), never holds the end of a session: a codec whose
 * driver hangs costs the wait of [JOIN_MS] and the line says it did not answer. Diagnostics only.
 */
object CodecMetrics {
    /** How long the end of a codec waits for its metrics before going on. */
    const val JOIN_MS = 300L

    private const val PREFIX = "android.media.mediacodec."
    private const val MAX_EXTRA = 8
    private const val MAX_VALUE = 40
    private const val US_PER_MS = 1000.0
    private val KNOWN = listOf(
        "codec", "mime", "mode", "encoder", "secure", "hardware", "width", "height", "rotation", "frame-rate", "bitrate_mode", "profile", "level",
        "errcode", "errstate",
    )
    private val LATENCY = listOf("latency.n", "latency.avg", "latency.min", "latency.max", "latency.unknown")
    private val EXTRA_WORDS = listOf("err", "drop", "count", "frames", "queue")

    /** `codec metrics (decoder c2.qti.avc.decoder): codec=… mime=video/avc … latency=n 1503 avg 9.2ms min 3.1ms max 98.0ms unknown 0 +4 keys`. */
    fun line(role: String, name: String?, values: Map<String, Any?>): String {
        val short = values.mapKeys { it.key.removePrefix(PREFIX) }
        val shown = KNOWN.filter { it in short }.map { "$it=${text(short[it])}" }.toMutableList()
        val latency = LATENCY.filter { it in short }
        if (latency.isNotEmpty()) shown += "latency=" + latency.joinToString(" ") { latencyPart(it, short[it]) }
        val used = KNOWN.toSet() + LATENCY.toSet()
        val extra = short.keys.filter { it !in used && EXTRA_WORDS.any { w -> w in it.lowercase(Locale.ROOT) } }.sorted()
        shown += extra.take(MAX_EXTRA).map { "$it=${text(short[it])}" }
        val more = short.size - used.count { it in short } - extra.take(MAX_EXTRA).size
        if (more > 0) shown += "+$more keys"
        return "codec metrics ($role ${name ?: "?"}): " + if (shown.isEmpty()) "none" else shown.joinToString(" ")
    }

    private fun latencyPart(key: String, value: Any?): String {
        val label = key.removePrefix("latency.")
        val n = (value as? Number) ?: return "$label ${text(value)}"
        // The framework gives the latencies in microseconds; the counts (n, unknown) are not times.
        return if (label == "n" || label == "unknown") "$label ${n.toLong()}" else "$label ${String.format(Locale.US, "%.1fms", n.toDouble() / US_PER_MS)}"
    }

    private fun text(value: Any?): String = when (value) {
        null -> "-"
        is Double, is Float -> String.format(Locale.US, "%.2f", (value as Number).toDouble())
        is IntArray, is LongArray, is DoubleArray, is Array<*> -> "[…]"
        else -> value.toString().replace(Regex("\\s+"), "_").take(MAX_VALUE)
    }

    /** The codec's metrics as a map, or null when the codec gave none (or it threw). */
    // Whatever a vendor driver throws; BaseBundle.get is deprecated from API 33 but is the only generic read.
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    fun read(codec: MediaCodec): Map<String, Any?>? = try {
        val bundle = codec.metrics
        bundle.keySet().associateWith { bundle.get(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Reads [codec]'s metrics on a thread of [BoundedRelease] and hands the line to [log]; waits at most [JOIN_MS]. A codec that does not
     * answer, or has none, is told in a line too and the caller goes on either way.
     */
    fun logBounded(role: String, name: String?, codec: MediaCodec, log: (String) -> Unit) {
        BoundedRelease.run(
            task = { log(read(codec)?.let { line(role, name, it) } ?: "codec metrics ($role ${name ?: "?"}): not available") },
            joinMs = JOIN_MS,
            onStuck = { log("codec metrics ($role ${name ?: "?"}): no answer in $JOIN_MS ms, given up") },
            threadName = THREAD_NAME,
        )
    }

    const val THREAD_NAME = "CodecMetrics"
}
