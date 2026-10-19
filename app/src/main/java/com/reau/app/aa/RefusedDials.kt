package com.reau.app.aa

/**
 * The dials of one [AaReceiver] loop that found no connection, for the line the log says when one comes at last: the
 * log only names the first refusal, so how many there were and for how long would otherwise not be known (the wait
 * after a stop, Android Auto's server coming up). [callIn]: Android Auto 17.3 and older, where the receiver asks
 * for a call instead of dialling.
 */
internal class RefusedDials(private val callIn: Boolean) {
    /** How many tries in a row found none. */
    var count = 0
        private set
    private var sinceMs = 0L

    /** A try found no connection, at [nowMs] (elapsed realtime). */
    fun refused(nowMs: Long) {
        if (count == 0) sinceMs = nowMs
        count++
    }

    /** A connection came at [nowMs]: the line to log if tries were refused before it (and the count starts again), else null. */
    fun connected(nowMs: Long): String? {
        if (count == 0) return null
        val tookMs = nowMs - sinceMs
        val what = if (callIn) "ask(s) for a call unanswered" else "dial(s) refused"
        return "connected at last after $count $what in ${tookMs / 1_000}.${tookMs % 1_000 / 100} s".also { count = 0 }
    }
}
