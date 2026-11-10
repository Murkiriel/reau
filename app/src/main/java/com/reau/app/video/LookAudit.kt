package com.reau.app.video

import java.util.Locale

/**
 * What the debug build's ride log says of the picture classifier ([PictureLook], [ScreenNet]), so that what the offline replica cannot reproduce
 * (it is not bit for bit the app: another decoder and another reduction; 04/11/2026 07:41:04, the app read "menu" at a p the replica put under
 * the limit) can be audited from the log: one line when the class of the look changes, and one when the net is not sure of itself (p under
 * [LOW_P]), at most one of the second kind per [lowGapMs] and [maxLines] in all, so a call card laid over the map (92 to 95 % for 33 s) cannot flood the log.
 * Pure: the caller gives the look and the clock.
 */
internal class LookAudit(private val maxLines: Int = MAX_LINES, private val lowGapMs: Long = LOW_GAP_MS) {
    private var last: PictureLook.Kind? = null
    private var lastLowAtMs = Long.MIN_VALUE / 2
    private var skipped = 0
    private var lines = 0
    private var stopped = false

    /** The line for this look ([p]: the net's confidence, 0 when the picture was gated as black), or null when there is nothing to say. */
    fun line(kind: PictureLook.Kind, p: Float, nowMs: Long): String? {
        val before = last
        last = kind
        val changed = before != kind
        val low = p > 0f && p < LOW_P
        if (!due(changed, low, nowMs)) return null
        if (low) lastLowAtMs = nowMs
        return write(kind, p, reason(before, changed, low))
    }

    private fun write(kind: PictureLook.Kind, p: Float, why: String): String {
        if (lines >= maxLines) return stop()
        lines++
        return String.format(Locale.US, "picture look: %s p=%.3f (%s)", kind, p, why)
    }

    /** Whether a look is to be written: its class changed, or the net was not sure and the last such line is a second old; never after the limit. */
    private fun due(changed: Boolean, low: Boolean, nowMs: Long): Boolean {
        if (!changed && !low) return false
        if (!changed && nowMs - lastLowAtMs < lowGapMs) {
            skipped++
            return false
        }
        return !stopped
    }

    private fun stop(): String {
        stopped = true
        return "picture look: $maxLines lines written, no more in this session"
    }

    private fun reason(before: PictureLook.Kind?, changed: Boolean, low: Boolean): String {
        val why = buildString {
            if (changed) append("was ${before ?: "nothing"}")
            if (changed && low) append(", ")
            if (low) append("p under $LOW_P")
            if (skipped > 0) append("; $skipped looks not written")
        }
        skipped = 0
        return why
    }

    companion object {
        /** A look whose confidence is under this is written (the abstention limit of the net is 0.9; the study's candidate for a new one, 0.98). */
        const val LOW_P = 0.98f
        const val LOW_GAP_MS = 1_000L
        const val MAX_LINES = 300
    }
}
