package com.reau.app.data

import java.util.Locale
import java.util.TreeMap

/**
 * What one projection was, summed up in one `resumo …` line when it is switched off ([line]):
 * how long, how long streaming, drops of the dash and of Android Auto, the dash's failed tries to connect, watchdogs by name, frames sent, the mean fps
 * (frames over the time streaming) and the lowest of the whole 5 s windows (counted from the start of streaming), the
 * longest gap between two frames, the hottest thermal status, the battery from the start to the last look, the
 * rider's marks, and when the dash was dark: the interruptions from the last frame delivered before the dash went to the first one
 * after it came back (`dark=2/3m02s (max 1m08s)`, only when there were some; `streaming=` stops when the dash drops, so the hole shows in no
 * other number). [ProjectionSummary] holds the one of the running projection, for the parts that feed it.
 *
 * Since 11/11/2026 the line ends with what the rest of it could not say: `buttons unknown=N (0x31=2 0x20=1)`, the dash's button codes REau has no
 * meaning for ([com.reau.app.aa.JoystickMap.knowsCode]); `maneuvers unknown=N (45=4)`, Android Auto's maneuver numbers with no glyph of their own
 * ([com.reau.app.dash.nav.AaManeuvers.knowsType]; counted when the number changes to one, not at each second it stays); and `no image=1m38s`, the
 * time the dash was left without the picture in all, from the last packet of the dash before each loss to the stream starting again
 * ([com.reau.app.dash.ImageGapClock.totalMs]). Only log: nothing is done differently for them.
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
    private var darkSince = -1L
    private var darkResumed = false
    private var darkCount = 0
    private var darkTotalMs = 0L
    private var darkMaxMs = 0L
    private val unknownButtons = TreeMap<Int, Int>()
    private val unknownManeuvers = TreeMap<Int, Int>()
    // The maneuver number last seen in each place (the one now, the one after), to count a number once per stretch.
    private val lastManeuver = IntArray(MANEUVER_PLACES) { NO_MANEUVER }

    fun start(nowMs: Long, batteryPct: Int?) = synchronized(lock) {
        startMs = nowMs
        batteryStart = batteryPct
        batteryLast = batteryPct
    }

    /** The video loop started ([on]) or stopped. */
    fun streaming(on: Boolean, nowMs: Long) = synchronized(lock) {
        if (on && darkSince >= 0) darkResumed = true // the video is back; its first frame ends the interruption
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
        if (darkSince >= 0 && darkResumed) closeDark(nowMs - darkSince)
        if (lastFrameMs >= 0) gapMaxMs = maxOf(gapMaxMs, nowMs - lastFrameMs)
        lastFrameMs = nowMs
        frames += count
        windowFrames += count
    }

    /**
     * A dash that was connected is gone. Not a try that failed: that is [dashTryFailed]. With [nowMs] (not negative) and some
     * frames already sent, the dark time begins at the last frame that went out, or now.
     */
    fun dashDropped(nowMs: Long = -1L) = synchronized(lock) {
        dashDrops++
        if (nowMs >= 0 && darkSince < 0 && frames > 0) {
            darkSince = lastFrameMs.takeIf { it >= 0 } ?: nowMs
            darkResumed = false
        }
    }

    private fun closeDark(ms: Long) {
        val dark = ms.coerceAtLeast(0)
        darkCount++
        darkTotalMs += dark
        darkMaxMs = maxOf(darkMaxMs, dark)
        darkSince = -1L
        darkResumed = false
    }

    /** A try to connect the dash (the first, or one to get it back) ended in failure. */
    fun dashTryFailed() = synchronized(lock) { dashFailedTries++ }

    fun aaDropped() = synchronized(lock) { aaDrops++ }

    /** A watchdog fired: the dash went silent, the decoder stalled… */
    fun watchdog(name: String) = synchronized(lock) { watchdogs.merge(name, 1, Int::plus) }

    /** A button of the dash with a [code] that REau has no meaning for. */
    fun unknownButton(code: Int) = synchronized(lock) { unknownButtons.merge(code and BYTE, 1, Int::plus) }

    /**
     * Android Auto's maneuver number [type] in [place] (0 the maneuver now, 1 the one after) was seen, [known] telling whether REau draws it.
     * An unknown one counts once each time the number changes to it, not at every second it stays.
     */
    fun maneuver(place: Int, type: Int, known: Boolean) = synchronized(lock) {
        val index = place.coerceIn(0, MANEUVER_PLACES - 1)
        if (type == lastManeuver[index]) return@synchronized
        lastManeuver[index] = type
        if (!known && type >= 0) unknownManeuvers.merge(type, 1, Int::plus)
    }

    /** A look at the phone ([DeviceState]). */
    fun device(thermal: Int?, batteryPct: Int?) = synchronized(lock) {
        if (thermal != null) thermalMax = maxOf(thermalMax ?: thermal, thermal)
        if (batteryPct != null) {
            if (batteryStart == null) batteryStart = batteryPct
            batteryLast = batteryPct
        }
    }

    /** [noImageMs]: the time without the picture so far, an unfinished one included (the image gap clock, which this class does not know). */
    fun line(nowMs: Long, marks: Int, noImageMs: Long = 0L): String = synchronized(lock) {
        val streamed = streamedMs + if (streamingSince >= 0) nowMs - streamingSince else 0L
        val avg = if (streamed > 0) String.format(Locale.US, "%.1f", frames * MS_PER_S / streamed) else "-"
        val min = if (windowMin.isNaN()) "-" else String.format(Locale.US, "%.1f", windowMin)
        val gap = if (gapMaxMs < 0) "-" else String.format(Locale.US, "%.1fs", gapMaxMs / MS_PER_S)
        // An interruption still going on counts up to now.
        val ongoing = if (darkSince >= 0) (nowMs - darkSince).coerceAtLeast(0) else -1L
        val count = darkCount + if (ongoing >= 0) 1 else 0
        val dark = if (count == 0) "" else " dark=$count/${duration(darkTotalMs + maxOf(ongoing, 0))} (max ${duration(maxOf(darkMaxMs, ongoing))})"
        val dogs = if (watchdogs.isEmpty()) "" else watchdogs.entries.joinToString(" ", " (", ")") { "${it.key}=${it.value}" }
        "resumo duration=${duration(nowMs - startMs)} streaming=${duration(streamed)} drops dash=$dashDrops aa=$aaDrops retries dash=$dashFailedTries " +
            "watchdogs=${watchdogs.values.sum()}$dogs frames=$frames fps avg=$avg min=$min gap max=$gap$dark " +
            "thermal max=${thermalName(thermalMax)} battery ${battery()} marks=$marks " +
            "buttons unknown=${unknownButtons.values.sum()}${listed(unknownButtons) { "0x%02X".format(it) }} " +
            "maneuvers unknown=${unknownManeuvers.values.sum()}${listed(unknownManeuvers) { it.toString() }} no image=${duration(noImageMs)}"
    }

    private fun listed(counts: Map<Int, Int>, name: (Int) -> String): String =
        if (counts.isEmpty()) "" else counts.entries.joinToString(" ", " (", ")") { "${name(it.key)}=${it.value}" }

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
        private const val BYTE = 0xFF
        private const val MANEUVER_PLACES = 2
        private const val NO_MANEUVER = Int.MIN_VALUE
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
