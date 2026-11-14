package com.reau.app.stream

import com.reau.app.dash.DashConfig

/**
 * The experimental live adjustment of the bitrate ([com.reau.app.settings.REauSettings.experimentAdaptiveBitrate]). An encoder asked for a
 * constant rate still puts out more than it was asked for on a busy picture, and every bit over the target is load on the dash's Wi-Fi. This
 * measures what the encoder really put out over [WINDOW_MS] and, against the bitrate the stream is meant to have ([target], set by
 * [StreamGovernor]):
 *
 * - above [HIGH_PERCENT] % of the target: the bitrate asked for comes down to `current × target / real`, never under [floorBps];
 * - under [LOW_PERCENT] % of it, with the asked bitrate below the target: it goes up by [RAISE_PERCENT] %, never over the target.
 *
 * The answer is a new bitrate for `DashEncoder.requestBitrate` (a live parameter, the encoder is never rebuilt), or null for no change. Pure:
 * the clock is passed in, so a JVM test runs it with numbers.
 */
internal class BitrateAdapter(private val floorBps: Int = DashConfig.EXPERIMENT_FLOOR_BPS) {
    private var targetBps = 0
    private var currentBps = 0
    private var windowStartMs = -1L
    private var windowBytes = 0L

    /** The bitrate the stream is meant to have, as last set. */
    val target: Int get() = targetBps

    /** The bitrate asked of the encoder now (the target, or what the adjustment made of it). */
    val current: Int get() = currentBps

    /** What the last window measured, in bits a second; 0 before the first one ends. */
    var lastRealBps = 0
        private set

    /** The stream is meant to have [bps] from now on (the governor changed its tier, or the encoder was rebuilt): the adjustment starts over from it. */
    fun setTarget(bps: Int) {
        targetBps = bps
        currentBps = bps
        windowStartMs = -1L
        windowBytes = 0L
    }

    /** [bytes] came out of the encoder since the previous call, at [nowMs]; the bitrate to ask for now, or null when it stays. */
    fun onOutput(nowMs: Long, bytes: Long): Int? {
        if (targetBps <= 0) return null
        if (windowStartMs < 0) {
            windowStartMs = nowMs
            windowBytes = 0L
            return null
        }
        windowBytes += bytes
        val elapsedMs = nowMs - windowStartMs
        return if (elapsedMs < WINDOW_MS) null else closeWindow(nowMs, windowBytes * BITS_PER_BYTE * MS_PER_S / elapsedMs)
    }

    /** The window ended at [nowMs] with [realBps] measured: the new bitrate to ask for, or null when it stays. */
    private fun closeWindow(nowMs: Long, realBps: Long): Int? {
        lastRealBps = realBps.toInt()
        windowStartMs = nowMs
        windowBytes = 0L
        val wanted = when {
            realBps * PERCENT > targetBps.toLong() * HIGH_PERCENT ->
                (currentBps.toLong() * targetBps / realBps).toInt().coerceIn(minOf(floorBps, targetBps), targetBps)
            realBps * PERCENT < targetBps.toLong() * LOW_PERCENT && currentBps < targetBps ->
                (currentBps.toLong() * (PERCENT + RAISE_PERCENT) / PERCENT).toInt().coerceAtMost(targetBps)
            else -> currentBps
        }
        return wanted.takeIf { it != currentBps }?.also { currentBps = it }
    }

    companion object {
        /** The adjustment the experimental switch asks for: one when it is [on], none (the governor's bitrate stands alone) when it is off. */
        fun whenOn(on: Boolean): BitrateAdapter? = if (on) BitrateAdapter() else null

        const val WINDOW_MS = 3_000L
        const val HIGH_PERCENT = 125L
        const val LOW_PERCENT = 70L
        const val RAISE_PERCENT = 15L
        private const val PERCENT = 100L
        private const val BITS_PER_BYTE = 8L
        private const val MS_PER_S = 1_000L
    }
}
