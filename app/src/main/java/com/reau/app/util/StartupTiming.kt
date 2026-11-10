package com.reau.app.util

import com.reau.app.data.RideDiagnostics

/**
 * Says in the ride log how long the three things that run on the main thread when REau opens took (`Application.onCreate`, the main
 * activity's `onResume`, the projection service's `onStartCommand`), but only when one took more than [THRESHOLD_MS]: a start under
 * it writes nothing. The first frame of A-13 (722 to 1 191 ms) has no per-call number to say which of them to blame; this gives it.
 * The rule is "more than", as [FrameJank.slow] has it: exactly [THRESHOLD_MS] is not slow, one millisecond above is.
 * The decisions are pure, so the JVM tests cover them.
 */
object StartupTiming {
    const val THRESHOLD_MS = 50L
    const val TAG = "startup"

    /** Whether something that took [ms] is worth a line. */
    fun slow(ms: Long): Boolean = ms > THRESHOLD_MS

    /** The line for [what] having taken [ms]. */
    fun line(what: String, ms: Long): String = "$what took $ms ms"

    /** Writes the line through [log] (tag, message) if [ms] is slow; nothing otherwise. True if it wrote. */
    fun report(what: String, ms: Long, log: (String, String) -> Unit = RideDiagnostics::log): Boolean {
        if (!slow(ms)) return false
        log(TAG, line(what, ms))
        return true
    }

    /** Runs [block] and reports how long it took, also when it throws. */
    inline fun <T> timed(
        what: String,
        noinline clock: () -> Long = MonotonicClock::nowMs,
        noinline log: (String, String) -> Unit = RideDiagnostics::log,
        block: () -> T,
    ): T {
        val begun = clock()
        try {
            return block()
        } finally {
            report(what, clock() - begun, log)
        }
    }
}
