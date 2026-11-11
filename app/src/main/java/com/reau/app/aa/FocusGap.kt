package com.reau.app.aa

/**
 * How long Android Auto is left without video focus when REau asks it for a keyframe (the focus is taken away and given back: the
 * protocol has no request for a keyframe). 100 ms is what REau always did. The experimental switch
 * ([com.reau.app.settings.REauSettings.experimentFocusGap]) makes it 400 ms: a loss and a gain too close together may be taken by the phone
 * for nothing at all. Which of the two brings the keyframe sooner is what [FocusCycleTiming] measures, with the switch on or off.
 */
internal object FocusGap {
    const val DEFAULT_MS = 100L
    const val EXPERIMENT_MS = 400L

    /** The gap for the switch: the experiment's when it is [on], the one REau always used when it is off. */
    fun ms(on: Boolean): Long = if (on) EXPERIMENT_MS else DEFAULT_MS

    /** What the line of the cycle says after its usual words: nothing with the usual gap, the gap when it is another. */
    fun words(gapMs: Long): String = if (gapMs == DEFAULT_MS) "" else " (experiment: the focus comes back after $gapMs ms, not $DEFAULT_MS)"
}

/**
 * Measures what a focus cycle bought: the time from the cycle to the next keyframe of Android Auto, written once per cycle, and a line when
 * none came in [OVERDUE_MS]. Only words for the log: nothing is decided from it. Called from the read thread and the session's timer.
 */
internal class FocusCycleTiming {
    private var cycledAtMs: Long? = null
    private var gapMs = 0L
    private var overdueSaid = false

    /** A cycle went out at [nowMs] with [gapMs] between the loss and the gain. */
    @Synchronized
    fun cycled(nowMs: Long, gapMs: Long) {
        cycledAtMs = nowMs
        this.gapMs = gapMs
        overdueSaid = false
    }

    /** A keyframe arrived at [nowMs]: the line for the log when it is the first since a cycle, else null. */
    @Synchronized
    fun keyframe(nowMs: Long): String? {
        val at = cycledAtMs ?: return null
        cycledAtMs = null
        return "focus cycle: the keyframe came ${nowMs - at} ms after the cycle (focus given back after $gapMs ms)"
    }

    /** Asked every second: the line, once per cycle, when no keyframe came [OVERDUE_MS] after it; else null. */
    @Synchronized
    fun overdue(nowMs: Long): String? {
        val at = cycledAtMs ?: return null
        if (overdueSaid || nowMs - at < OVERDUE_MS) return null
        overdueSaid = true
        return "focus cycle: no keyframe ${nowMs - at} ms after the cycle (focus given back after $gapMs ms)"
    }

    companion object {
        /** Android Auto answers a cycle within a second or two when it answers at all. */
        const val OVERDUE_MS = 5_000L
    }
}
