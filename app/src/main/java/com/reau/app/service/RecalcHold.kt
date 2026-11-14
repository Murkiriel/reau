package com.reau.app.service

import com.reau.app.aa.NavGuidance

/**
 * Between the guidance of Android Auto's navigation status and the dash's route card: a short "inactive" during a route shown as a recalculation.
 * The real recalculations reach the dash as the status "inactive" for 1 to 7 s between two "active" ones (ride logs of November 2026), and the route
 * card cleared at each. With the experimental switch ([on]) such an "inactive" is not passed on; the last active guidance goes instead with
 * `rerouting` set (the dash's spinning compass, or the lost-signal icon if the phone has no network) and the last distances. The next active
 * guidance replaces it; after [HOLD_MS] with none, the "inactive" goes on as it came and the card clears.
 *
 * What is not held, so a route that really ended clears as always: an "inactive" that did not come from the status message (the end of a
 * session, a cluster stop), one after a last maneuver of arrival or within [ARRIVED_METERS] of the destination, and everything with the switch off,
 * when [offer] passes each guidance straight to [deliver] and keeps nothing.
 *
 * [schedule] runs an action after a delay and returns the way to cancel it. The messages come on Android Auto's thread and the timer on another one.
 */
internal class RecalcHold(
    private val on: () -> Boolean,
    private val deliver: (NavGuidance) -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> () -> Unit,
    private val holdMs: Long = HOLD_MS,
    private val clockMs: () -> Long = { 0L },
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var last: NavGuidance? = null
    private var holding = false
    private var cancelTimer: (() -> Unit)? = null
    private var epoch = 0
    private var heldSinceMs = 0L

    fun offer(guidance: NavGuidance) {
        val out: NavGuidance? = synchronized(lock) {
            when {
                !on() -> {
                    release()
                    last = null
                    guidance
                }
                guidance.active -> {
                    if (holding) log(backLine(clockMs() - heldSinceMs))
                    release()
                    last = guidance
                    guidance
                }
                holding && guidance.endedByStatus -> null
                guidance.endedByStatus && recalculationLooks(last) -> hold(checkNotNull(last))
                else -> {
                    release()
                    last = null
                    guidance
                }
            }
        }
        if (out != null) deliver(out)
    }

    /** Starts holding: the guidance to show now, and the timer that ends it. */
    private fun hold(last: NavGuidance): NavGuidance {
        holding = true
        heldSinceMs = clockMs()
        log("route: a short inactive shown as a recalculation; holding it up to ${holdMs / MS_PER_S} s (experiment)")
        val mine = ++epoch
        cancelTimer = schedule(holdMs) { expired(mine) }
        return last.copy(rerouting = true)
    }

    private fun expired(mine: Int) {
        synchronized(lock) {
            if (!holding || mine != epoch) return
            release()
            last = null
            log("route: the inactive did not end in ${holdMs / MS_PER_S} s; the route card is cleared (experiment)")
        }
        deliver(NavGuidance.INACTIVE)
    }

    /** Stops holding and the timer (under the lock); nothing to do when nothing is held. */
    private fun release() {
        if (!holding) return
        holding = false
        epoch++
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /** How long a short "inactive" stays a recalculation: a little over the longest one seen (7 s). */
        const val HOLD_MS = 8_000L

        private const val MS_PER_S = 1_000L

        /** The log's line when the guidance came back [ms] after the "inactive" that was held. */
        internal fun backLine(ms: Long): String {
            val seconds = String.format(java.util.Locale.US, "%.1f", ms.coerceAtLeast(0L) / MS_PER_S.toDouble())
            return "route: the guidance is back after $seconds s of inactive (experiment)"
        }

        /** A route this close to its destination that goes "inactive" has arrived. */
        const val ARRIVED_METERS = 100

        /** Android Auto's NavigationType numbers of an arrival (destination, left, right). */
        private val ARRIVALS = 39..42

        /** Whether an "inactive" after [last] may be a recalculation: a route ran, and it was not at its end. */
        internal fun recalculationLooks(last: NavGuidance?): Boolean =
            last != null && last.active && last.maneuverType !in ARRIVALS && last.distanceRemainingMeters !in 0..ARRIVED_METERS
    }
}
