package com.reau.app.stream

/**
 * Automatic keyframe interval, 1 or 2 s, decided once per minute of
 * moving windows so the encoder isn't rebuilt more often than that:
 * - any window where the dash acknowledged decoding less than [DELIVERY_RATIO] of what was sent
 *   means loss or decoder stress → 1 s, so a full picture comes back sooner;
 * - otherwise, the encoder producing more than [BUSY_RATIO] of the applied bitrate means the bit
 *   budget is tight → 2 s, spending fewer bits on repeated full frames;
 * - neither: keep the current interval.
 * Pure logic, unit-tested; the ride log records every decision and its reason.
 */
internal class AutoKeyframeController {

    data class Window(
        val sentFps: Double,
        /** Null when the dash's decode acks aren't usable — then only the bitrate rule applies. */
        val decodedFps: Double?,
        val kbps: Double,
        val appliedKbps: Double,
        val allActive: Boolean,
    )

    data class Decision(val intervalSec: Int, val changed: Boolean, val reason: String)

    var currentSec: Int = 1
        private set

    private var windows = 0
    private var shortfall = false
    private var kbpsSum = 0.0
    private var appliedKbpsSum = 0.0

    fun onWindow(w: Window): Decision {
        if (w.allActive) accumulate(w)
        return when {
            !w.allActive -> hold("idle window, not counted")
            windows < WINDOWS_PER_DECISION -> hold("collecting $windows/$WINDOWS_PER_DECISION")
            else -> decide()
        }
    }

    private fun accumulate(w: Window) {
        windows++
        val decoded = w.decodedFps
        if (decoded != null && w.sentFps > 0 && decoded < w.sentFps * DELIVERY_RATIO) shortfall = true
        kbpsSum += w.kbps
        appliedKbpsSum += w.appliedKbps
    }

    private fun decide(): Decision {
        val busy = appliedKbpsSum > 0 && kbpsSum / appliedKbpsSum > BUSY_RATIO
        val usage = if (appliedKbpsSum > 0) 100 * kbpsSum / appliedKbpsSum else 0.0
        val (target, why) = when {
            shortfall -> 1 to "dash decoded < ${(DELIVERY_RATIO * 100).toInt()}% of sent in the last minute"
            busy -> 2 to "kbps at %.0f%% of applied".format(java.util.Locale.US, usage)
            else -> currentSec to "kbps at %.0f%% of applied, dash clean".format(java.util.Locale.US, usage)
        }
        windows = 0
        shortfall = false
        kbpsSum = 0.0
        appliedKbpsSum = 0.0
        val changed = target != currentSec
        currentSec = target
        return Decision(target, changed, why)
    }

    private fun hold(reason: String) = Decision(currentSec, changed = false, reason = reason)

    companion object {
        /** 12 windows of 5 s = one decision per minute of riding. */
        const val WINDOWS_PER_DECISION = 12
        const val DELIVERY_RATIO = 0.95
        const val BUSY_RATIO = 0.70
    }
}
