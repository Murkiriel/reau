package com.reau.app.data

import java.util.Locale
import java.util.TreeMap

/**
 * What one projection was, summed up in one `resumo …` line when it is switched off ([line]):
 * how long, how long streaming, drops of the dash and of Android Auto, the dash's failed tries to connect, watchdogs by name, frames sent, the mean fps
 * (frames over the time streaming) and the lowest of the whole 5 s windows (counted from the start of streaming), the
 * longest gap between two frames, the hottest thermal status, the battery from the start to the last look, the
 * rider's marks. [ProjectionSummary] holds the one of the running projection, for the parts that feed it.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
class RideSummary(private val windowMs: Long = WINDOW_MS) {
    private val lock = Any()
    private var startMs = 0L
    private var batteryStart: Int? = null
    private var batteryLast: Int? = null
    private var thermalMax: Int? = null
    private var dashDrops = 0
    private var dashFailedTries = 0
    private var aaDrops = 0
    private val watchdogs = TreeMap<String, Int>()
    private var streamingSince = -1L
    private var streamedMs = 0L
    private var frames = 0L
    private var lastFrameMs = -1L
    private var gapMaxMs = -1L
    private var windowStart = -1L
    private var windowFrames = 0
    private var windowMin = Double.NaN

    fun start(nowMs: Long, batteryPct: Int?) = synchronized(lock) {
        startMs = nowMs
        batteryStart = batteryPct
        batteryLast = batteryPct
    }

    /** The video loop started ([on]) or stopped. */
    fun streaming(on: Boolean, nowMs: Long) = synchronized(lock) {
        if (on && streamingSince < 0) {
            streamingSince = nowMs
            windowStart = nowMs
            windowFrames = 0
            lastFrameMs = -1L
        } else if (!on && streamingSince >= 0) {
            closeWindows(nowMs)
            streamedMs += nowMs - streamingSince
            streamingSince = -1L
        }
    }

    /** [count] frames went out to the dash by [nowMs]. */
    fun frames(count: Int, nowMs: Long) = synchronized(lock) {
        if (count <= 0) return@synchronized
        if (windowStart >= 0) closeWindows(nowMs)
        if (lastFrameMs >= 0) gapMaxMs = maxOf(gapMaxMs, nowMs - lastFrameMs)
        lastFrameMs = nowMs
        frames += count
        windowFrames += count
    }

    /** A dash that was connected is gone. Not a try that failed: that is [dashTryFailed]. */
    fun dashDropped() = synchronized(lock) { dashDrops++ }

    /** A try to connect the dash (the first, or one to get it back) ended in failure. */
    fun dashTryFailed() = synchronized(lock) { dashFailedTries++ }

    fun aaDropped() = synchronized(lock) { aaDrops++ }

    /** A watchdog fired: the dash went silent, the decoder stalled… */
    fun watchdog(name: String) = synchronized(lock) { watchdogs.merge(name, 1, Int::plus) }

    /** A look at the phone ([DeviceState]). */
    fun device(thermal: Int?, batteryPct: Int?) = synchronized(lock) {
        if (thermal != null) thermalMax = maxOf(thermalMax ?: thermal, thermal)
        if (batteryPct != null) {
            if (batteryStart == null) batteryStart = batteryPct
            batteryLast = batteryPct
        }
    }

    fun line(nowMs: Long, marks: Int): String = synchronized(lock) {
        val streamed = streamedMs + if (streamingSince >= 0) nowMs - streamingSince else 0L
        val avg = if (streamed > 0) String.format(Locale.US, "%.1f", frames * MS_PER_S / streamed) else "-"
        val min = if (windowMin.isNaN()) "-" else String.format(Locale.US, "%.1f", windowMin)
        val gap = if (gapMaxMs < 0) "-" else String.format(Locale.US, "%.1fs", gapMaxMs / MS_PER_S)
        val dogs = if (watchdogs.isEmpty()) "" else watchdogs.entries.joinToString(" ", " (", ")") { "${it.key}=${it.value}" }
        "resumo duration=${duration(nowMs - startMs)} streaming=${duration(streamed)} drops dash=$dashDrops aa=$aaDrops retries dash=$dashFailedTries " +
            "watchdogs=${watchdogs.values.sum()}$dogs frames=$frames fps avg=$avg min=$min gap max=$gap " +
            "thermal max=${thermalName(thermalMax)} battery ${battery()} marks=$marks"
    }

    /** Closes every whole window that ended by [nowMs]. */
    private fun closeWindows(nowMs: Long) {
        while (nowMs >= windowStart + windowMs) {
            val fps = windowFrames * MS_PER_S / windowMs
            windowMin = if (windowMin.isNaN()) fps else minOf(windowMin, fps)
            windowStart += windowMs
            windowFrames = 0
        }
    }

    private fun battery(): String {
        val a = batteryStart ?: return "?"
        val b = batteryLast ?: a
        return "$a→$b% (${if (b - a > 0) "+" else ""}${b - a})"
    }

    companion object {
        const val WINDOW_MS = 5_000L
        private const val MS_PER_S = 1000.0
        private const val S_PER_MIN = 60
        private val THERMAL_NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

        private fun thermalName(status: Int?) = status?.let { THERMAL_NAMES.getOrElse(it) { n -> "status$n" } } ?: "?"

        private fun duration(ms: Long): String {
            val s = (ms / MS_PER_S).toLong()
            return if (s < S_PER_MIN) "${s}s" else String.format(Locale.US, "%dm%02ds", s / S_PER_MIN, s % S_PER_MIN)
        }
    }
}

/** The summary of the projection running now: renewed by the service at each start, fed by the parts it runs. */
object ProjectionSummary {
    @Volatile var current = RideSummary()
}
