package com.reau.app.stream

import com.reau.app.dash.DashConfig

/**
 * Automatic stream fps: climbs in even steps toward
 * [DashConfig.MAX_STREAM_FPS] while every measurement window shows the dash really keeping up, and
 * backs off to what was actually delivered when it doesn't. The bitrate stays pinned at the
 * [DashConfig.MAX_STREAM_BITRATE_BPS] ceiling (automatic fps always uses
 * the full 1024 kbps, the Settings slider is disabled), so settling lower buys more bits per frame.
 *
 * "Keeping up" means, over one window: the phone sent ≥ [DELIVERY_RATIO] of the target, the dash
 * acknowledged decoding ≥ [DELIVERY_RATIO] of it (09 06/09 04 acks, see `DashSession`), the encoder
 * stayed within the bitrate ceiling and the phone isn't thermally throttling. Windows where the map
 * sat still (the loop's own half-rate idle profile) say nothing about capacity and are skipped.
 *
 * **The dash's side.** On a real dash the acks do NOT track frames, so in practice
 * only the phone's own delivery is judged — and since the frame loop does not wait for the encoder
 * the phone delivers whatever is asked. Measured on a real dash: the rate climbed 20 → 30 fps in 80 s, the dash went
 * silent at 30 with its Wi-Fi still up, and every reconnection restarted at the remembered 30. Hence:
 * - a [ceiling] the climb never passes — the user's own choice in Settings, [DashConfig.AUTO_FPS_CEILING] (what
 *   the dash has been SEEN to hold) until they move it, lowered by what it has been seen to fail at ([plan]);
 * - [plan]: a stream the dash stopped answering while it stayed reachable restarts at half that rate.
 *
 * Pure logic (no Android, clock injected per call) so every rule is unit-tested.
 */
internal class AutoFpsController(startFps: Int, ceiling: Int = DashConfig.MAX_STREAM_FPS) {

    /** How a stream starts: at [startFps], never climbing past [ceiling]; [dashLimit] is what to remember (the
     *  highest rate still allowed after the dash failed at one), null when it never did. */
    data class Plan(val startFps: Int, val ceiling: Int, val dashLimit: Int?)

    /** The climb's top: an even step, like every rate. */
    val ceiling = DashConfig.snapStreamFps(ceiling)

    data class Window(
        val targetFps: Int,
        val sentFps: Double,
        /** Frames/s the dash acknowledged decoding; null when no ack count is available. */
        val decodedFps: Double?,
        val kbps: Double,
        /** True only if every frame of the window went out in the active (moving) profile. */
        val allActive: Boolean,
        val thermalOk: Boolean,
    )

    data class Decision(val fps: Int, val changed: Boolean, val reason: String)

    var currentFps: Int = DashConfig.snapStreamFps(startFps).coerceAtMost(this.ceiling)
        private set

    /** Highest level that has passed [UP_AFTER_HEALTHY] windows in a row — what gets remembered
     *  for the next ride. */
    var stableFps: Int? = null
        private set

    /** Whether the dash's decode acks track frames 1:1. Null while still calibrating; false means
     *  they're ignored and only the phone's own send rate is judged. */
    var acksTrusted: Boolean? = null
        private set

    /** Windows that were actually judged (moving, settled, at the current target). */
    var judgedWindows = 0
        private set
    // fps level -> [healthy windows, short windows]
    private val levelStats = java.util.TreeMap<Int, IntArray>()
    private var peakDecodedFps = 0.0
    private var peakKbps = 0.0

    private var healthyStreak = 0
    private var unhealthyStreak = 0
    private var settleWindows = 1
    private var lowAckWindows = 0
    private val blockedUntilMs = HashMap<Int, Long>()
    private val failures = HashMap<Int, Int>()

    fun onWindow(w: Window, nowMs: Long): Decision = when {
        w.targetFps != currentFps -> hold("window measured at ${w.targetFps}, not $currentFps")
        !w.allActive -> hold("idle window (map still), not judged")
        settleWindows > 0 -> {
            settleWindows--
            hold("settling after change")
        }
        else -> judge(w, nowMs)
    }

    private fun judge(w: Window, nowMs: Long): Decision {
        calibrateAcks(w)
        val deliveredOk = w.sentFps >= currentFps * DELIVERY_RATIO
        val decodedOk = acksTrusted != true || (w.decodedFps ?: 0.0) >= currentFps * DELIVERY_RATIO
        val bitrateOk = w.kbps <= DashConfig.MAX_STREAM_BITRATE_BPS / 1000.0 * BITRATE_TOLERANCE
        val healthy = deliveredOk && decodedOk && bitrateOk && w.thermalOk
        judgedWindows++
        levelStats.getOrPut(currentFps) { IntArray(2) }[if (healthy) 0 else 1]++
        if (acksTrusted != false) peakDecodedFps = maxOf(peakDecodedFps, w.decodedFps ?: 0.0)
        peakKbps = maxOf(peakKbps, w.kbps)
        return if (healthy) onHealthy(nowMs) else onUnhealthy(w, nowMs, deliveredOk, decodedOk, bitrateOk)
    }

    /** What the dash has shown it can take so far this session, for the ride log. Per level:
     *  healthy/short judged windows. */
    fun summary(): String {
        if (judgedWindows == 0) return "no judged windows yet (moving windows only)"
        val levels = levelStats.entries.joinToString(" ") { (fps, s) -> "$fps:${s[0]}/${s[1]}" }
        return String.format(
            java.util.Locale.US,
            "best stable %s fps | ok/short per fps %s | peak dash %.1f fps | peak kbps %.0f | acks trusted=%s | judged %d",
            stableFps?.toString() ?: "none", levels, peakDecodedFps, peakKbps, acksTrusted, judgedWindows,
        )
    }

    private fun calibrateAcks(w: Window) {
        if (acksTrusted != null) return
        val decoded = w.decodedFps
        if (decoded == null || w.sentFps <= 0.0) {
            acksTrusted = false
            return
        }
        // A dash that decodes but acks only keyframes (~1/s) would otherwise read as a massive
        // shortfall and drive the rate to the floor. Genuine overload doesn't cut decoding below
        // this fraction while frames still flow, so two such windows mean "acks don't track frames".
        if (decoded / w.sentFps >= ACK_TRACKING_MIN_RATIO) {
            acksTrusted = true
        } else if (++lowAckWindows >= CALIBRATION_WINDOWS) {
            acksTrusted = false
        }
    }

    private fun onHealthy(nowMs: Long): Decision {
        unhealthyStreak = 0
        healthyStreak++
        if (healthyStreak >= UP_AFTER_HEALTHY) stableFps = currentFps
        val next = currentFps + DashConfig.STREAM_FPS_STEP
        val blocked = blockedUntilMs[next] ?: 0L
        return when {
            healthyStreak < UP_AFTER_HEALTHY -> hold("healthy $healthyStreak/$UP_AFTER_HEALTHY")
            next > ceiling -> hold("at ceiling")
            nowMs < blocked -> hold("$next fps failed recently, retry in ${(blocked - nowMs) / 1000}s")
            else -> change(next, "up: $UP_AFTER_HEALTHY healthy windows at $currentFps")
        }
    }

    private fun onUnhealthy(w: Window, nowMs: Long, deliveredOk: Boolean, decodedOk: Boolean, bitrateOk: Boolean): Decision {
        healthyStreak = 0
        unhealthyStreak++
        val why = buildList {
            if (!deliveredOk) add("sent %.1f".format(w.sentFps))
            if (!decodedOk) add("dash %.1f".format(w.decodedFps ?: 0.0))
            if (!bitrateOk) add("kbps %.0f".format(w.kbps))
            if (!w.thermalOk) add("thermal")
        }.joinToString(", ")
        return when {
            unhealthyStreak < DOWN_AFTER_UNHEALTHY -> hold("short ($why) $unhealthyStreak/$DOWN_AFTER_UNHEALTHY")
            currentFps <= DashConfig.MIN_STREAM_FPS -> hold("at floor ($why)")
            else -> stepDown(w, nowMs, why)
        }
    }

    private fun stepDown(w: Window, nowMs: Long, why: String): Decision {
        val failed = currentFps
        val count = (failures[failed] ?: 0) + 1
        failures[failed] = count
        val backoff = (BASE_BACKOFF_MS shl (count - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS))
        blockedUntilMs[failed] = nowMs + backoff

        // Drop straight to what was really delivered (rounded down to even), at least one step.
        val delivered = if (acksTrusted == true) minOf(w.sentFps, w.decodedFps ?: w.sentFps) else w.sentFps
        val target = DashConfig.snapStreamFps(delivered.toInt())
            .coerceAtMost(failed - DashConfig.STREAM_FPS_STEP)
            .coerceAtLeast(DashConfig.MIN_STREAM_FPS)
        stableFps?.let { if (it >= failed) stableFps = target }
        return change(target, "down: $why at $failed, blocked ${backoff / 1000}s")
    }

    private fun change(fps: Int, reason: String): Decision {
        currentFps = fps
        healthyStreak = 0
        unhealthyStreak = 0
        settleWindows = 1
        return Decision(fps, changed = true, reason = reason)
    }

    private fun hold(reason: String) = Decision(currentFps, changed = false, reason = reason)

    companion object {
        /**
         * How the next stream starts. [storedStable] is the rate remembered from before, [dashLimit] the limit
         * learned from an earlier failure (null: none in force), [failedFps] the rate of the stream that has just
         * died with the dash still reachable (null: this is a normal start), [riderCeiling] how high the rider lets
         * it climb (the fps control in Settings; [DashConfig.AUTO_FPS_CEILING] until they move it).
         *
         * After such a failure the failed rate itself is out (the limit drops to one step below it, and never goes
         * back up here) and the stream restarts at HALF of it — not one step down: at 30 fps the dash did not even
         * confirm a first frame, so the next try has to be clearly inside what it can take.
         */
        fun plan(storedStable: Int, dashLimit: Int?, failedFps: Int?, riderCeiling: Int = DashConfig.AUTO_FPS_CEILING): Plan {
            val afterFailure = failedFps?.let { DashConfig.snapStreamFps(it - DashConfig.STREAM_FPS_STEP) }
            val limit = listOfNotNull(dashLimit, afterFailure).minOrNull()
            val wanted = DashConfig.snapStreamFps(riderCeiling)
            val ceiling = minOf(wanted, limit ?: wanted)
            val start = if (failedFps != null) DashConfig.snapStreamFps(failedFps / 2) else DashConfig.snapStreamFps(storedStable)
            return Plan(start.coerceAtMost(ceiling), ceiling, limit)
        }

        const val DELIVERY_RATIO = 0.95
        /** Headroom over the ceiling before a window counts as over budget: the encoder is configured
         *  at the ceiling, and VBR's 5 s average wobbles a few percent around its target. */
        const val BITRATE_TOLERANCE = 1.10
        const val ACK_TRACKING_MIN_RATIO = 0.3
        const val CALIBRATION_WINDOWS = 2
        const val UP_AFTER_HEALTHY = 3
        const val DOWN_AFTER_UNHEALTHY = 2
        const val BASE_BACKOFF_MS = 120_000L
        const val MAX_BACKOFF_DOUBLINGS = 3
    }
}
