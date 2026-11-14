package com.reau.app.util

import java.util.Locale
import kotlin.math.ceil

/**
 * The durations of one stage of the video path (the compositor's draw, a frame's time in the encoder, a picture's look, the latch of a
 * new picture) over a stretch of time, kept without allocating per sample: three numbers and a fixed histogram. It is what lets the
 * `video fps` line say the worst frame and the 95th percentile of a window, not only the mean ([StageStats]); the mean alone hid a frame
 * of 20 ms among fifty of 3.
 *
 * One thread adds (the GL thread, the encoder's output thread), another takes (the video loop's poll), so both are synchronized: an
 * uncontended lock, a few dozen times a second at most. [take] allocates its answer once per poll, never per sample.
 */
class StageClock {
    private var count = 0
    private var sumNs = 0L
    private var maxNs = 0L
    private val buckets = IntArray(StageStats.BUCKETS)

    /** One sample of [ns] (a negative one, from a clock gone back, counts as 0). */
    @Synchronized
    fun add(ns: Long) {
        val d = ns.coerceAtLeast(0L)
        count++
        sumNs += d
        if (d > maxNs) maxNs = d
        buckets[StageStats.bucketOf(d)]++
    }

    /** What was added since the last call; it starts again. */
    @Synchronized
    fun take(): StageStats {
        if (count == 0) return StageStats.EMPTY
        val taken = StageStats(count, sumNs, maxNs, buckets.copyOf())
        count = 0
        sumNs = 0L
        maxNs = 0L
        buckets.fill(0)
        return taken
    }
}

/**
 * The samples of a stretch of time: how many, their sum and worst, and a histogram of [BUCKET_NS] steps (the last bucket holds all that
 * are longer) that answers a percentile to within one step.
 */
class StageStats(val count: Int, val sumNs: Long, val maxNs: Long, internal val buckets: IntArray) {
    val meanMs: Double get() = if (count == 0) 0.0 else sumNs / NS_PER_MS / count
    val maxMs: Double get() = maxNs / NS_PER_MS

    /**
     * The 95th percentile in ms: the top edge of the bucket the 95th sample falls in, never over the worst sample. The step is 0.5 ms, so
     * it reads at most 0.5 ms high. A sample past the last bucket (64 ms) reads as the worst one.
     */
    val p95Ms: Double get() = percentileMs(P95)

    private fun percentileMs(p: Double): Double {
        if (count == 0) return 0.0
        val rank = ceil(p * count).toInt().coerceIn(1, count)
        var seen = 0
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= rank) {
                return if (i == BUCKETS - 1) maxMs else minOf((i + 1) * BUCKET_NS / NS_PER_MS, maxMs)
            }
        }
        return maxMs
    }

    companion object {
        /** No sample at all. */
        val EMPTY = StageStats(0, 0L, 0L, IntArray(0))

        /** The histogram's step; 128 steps cover 64 ms, and one more bucket holds the rest. */
        const val BUCKET_NS = 500_000L
        const val BUCKETS = 129
        private const val NS_PER_MS = 1_000_000.0
        private const val P95 = 0.95

        /** The bucket a sample of [ns] falls in. */
        fun bucketOf(ns: Long): Int = (ns / BUCKET_NS).coerceAtMost((BUCKETS - 1).toLong()).toInt()
    }
}

/** The [StageStats] of the polls of one window, added up. */
class StageTotals {
    private var count = 0
    private var sumNs = 0L
    private var maxNs = 0L
    private val buckets = IntArray(StageStats.BUCKETS)

    fun add(stats: StageStats) {
        if (stats.count == 0) return
        count += stats.count
        sumNs += stats.sumNs
        if (stats.maxNs > maxNs) maxNs = stats.maxNs
        for (i in stats.buckets.indices) buckets[i] += stats.buckets[i]
    }

    /** Everything added so far as one [StageStats]. */
    fun stats(): StageStats = if (count == 0) StageStats.EMPTY else StageStats(count, sumNs, maxNs, buckets.copyOf())
}

/**
 * The fields the `video fps` line carries after its original ones (since 03/11/2026; the original ones keep their place and
 * format, so what reads the line keeps reading it), for `scripts/registro/ler_registro.py`:
 *
 * `compose_max=7.6ms compose_p95=5.0ms encode_max=19.2ms encode_p95=17.0ms look=3/0.90/1.40ms latch=152/0.20/0.90ms wake=152(30.4/s)`
 *
 * - `compose_*`: the worst and the 95th percentile of the compositor's frames (the draw and the post to the encoder), `-` with no frame.
 * - `encode_*`: the same for the time a frame spends inside the encoder.
 * - `look=n/mean/max`: the picture looks (the shrunk read-back and the classifier), counted apart from the compose; `look=0` with none.
 * - `latch=n/mean/max`: the latch of each new picture from Android Auto (`updateTexImage`), also outside the compose.
 * - `wake=N(rate/s)`: the pictures from Android Auto that woke the compositor in the window, against the `drawn=` of the line.
 */
object StageReport {
    @Suppress("LongParameterList") // the five measurements of one window and its length, which the line prints in turn
    fun text(compose: StageStats, encode: StageStats, look: StageStats, latch: StageStats, wakeups: Int, elapsedMs: Long): String {
        val seconds = elapsedMs / MS_PER_S
        return String.format(
            Locale.US,
            " compose_max=%s compose_p95=%s encode_max=%s encode_p95=%s look=%s latch=%s wake=%d(%.1f/s)",
            ms(compose, compose.maxMs), ms(compose, compose.p95Ms), ms(encode, encode.maxMs), ms(encode, encode.p95Ms),
            triple(look), triple(latch), wakeups, if (seconds > 0) wakeups / seconds else 0.0,
        )
    }

    private fun ms(stats: StageStats, value: Double): String = if (stats.count == 0) "-" else String.format(Locale.US, "%.1fms", value)

    private fun triple(stats: StageStats): String =
        if (stats.count == 0) "0" else String.format(Locale.US, "%d/%.2f/%.2fms", stats.count, stats.meanMs, stats.maxMs)

    private const val MS_PER_S = 1000.0
}
