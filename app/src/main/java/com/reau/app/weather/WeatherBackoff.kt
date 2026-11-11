package com.reau.app.weather

/**
 * How long the weather waits to ask again after a request that went out and gave nothing usable: 1, 2, 4, 8 and then 15 minutes
 * (never more), back to normal at the first answer that works. Before, a failure was tried again every minute for ever, in a stretch
 * with no signal, spending data and battery on a service that was not answering. Pure: the caller hands in the clock
 * ([com.reau.app.util.MonotonicClock]) and decides what a failure is (only a request that was sent: with no network there is nothing
 * to spend, and the first minute with one is the wanted one).
 */
internal class WeatherBackoff {
    /** The failures in a row since the last answer that worked. */
    var failures = 0
        private set
    private var retryAtMs = 0L

    /** Whether a new request may go out at [nowMs]: always with no failure pending, else only once the wait is over. */
    fun mayTry(nowMs: Long): Boolean = failures == 0 || nowMs >= retryAtMs

    /** A request failed at [nowMs]; returns how long to wait before the next one. */
    fun failed(nowMs: Long): Long {
        failures += 1
        val wait = waitMs(failures)
        retryAtMs = nowMs + wait
        return wait
    }

    /** An answer that worked: the waits start over. */
    fun succeeded() = reset()

    fun reset() {
        failures = 0
        retryAtMs = 0L
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /** The wait after the 1st, 2nd, ... failure in a row; the last is the ceiling. */
        val STEPS_MS = longArrayOf(1 * MINUTE_MS, 2 * MINUTE_MS, 4 * MINUTE_MS, 8 * MINUTE_MS, 15 * MINUTE_MS)

        /** The wait after [failures] failures in a row (1 or more). */
        fun waitMs(failures: Int): Long = STEPS_MS[(failures - 1).coerceIn(0, STEPS_MS.lastIndex)]
    }
}
