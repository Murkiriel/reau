package com.reau.app.stream

import com.reau.app.aa.NavGuidance

/**
 * The bike's speed for the ride log, with no location permission: how fast the
 * distance to the next maneuver goes down, from the route Android Auto sends while Waze or Maps navigates. A projection
 * service cannot read the GPS with the screen off unless it declares the location type, and the dash's telemetry is all
 * zero over this link, so this is the speed there is: none
 * without a route, and an approximation (what matters is telling 40 from 80 and 100 km/h).
 *
 * The readings of the last [SPAN_MS] count; a distance that grows is a new maneuver, and the count starts again; so does
 * one that falls more than [JUMP_METERS] at once (Waze says whole km from 10 km up: 11 -> 10 km in 6 s read as
 * 556 km/h; a new route does the same); the same distance said again (another message carrying it) is no new reading.
 * Fed on Android Auto's thread and read on the video loop's, hence synchronized.
 */
internal class NavSpeed {
    private class Reading(val atMs: Long, val meters: Int)

    private val readings = ArrayDeque<Reading>()

    /** A route with a distance is running now; what [displayKmhAt] needs to tell "unknown" from "not navigating". */
    private var routeActive = false
    private var heldKmh = 0.0
    private var heldAtMs = Long.MIN_VALUE

    /** The last speed worked out from [MIN_SPAN_MS] or more of readings, and when: it stands through a new maneuver's first seconds. */
    private var goodKmh = 0.0
    private var goodAtMs = Long.MIN_VALUE

    @Synchronized
    fun onGuidance(guidance: NavGuidance, atMs: Long) {
        val meters = guidance.distanceToManeuverMeters
        routeActive = guidance.active && meters >= 0
        if (!routeActive) {
            heldAtMs = Long.MIN_VALUE
            goodAtMs = Long.MIN_VALUE
            return readings.clear()
        }
        val last = readings.lastOrNull()
        if (last != null && meters == last.meters) return
        if (last != null && (meters > last.meters || last.meters - meters > JUMP_METERS)) {
            captureGood(last.atMs) // the speed before the turn (or the jump) is kept: the new count has too few readings for a while
            readings.clear()
        }
        readings.addLast(Reading(atMs, meters))
        while (readings.size > 1 && atMs - readings.first().atMs > SPAN_MS) readings.removeFirst()
    }

    /**
     * km/h at [nowMs], or null: no route, or no reading for [STALE_MS]. Waze says the distance in steps of 100 m, so
     * two readings a few seconds apart say a speed that is wrong by tens of km/h (177 km/h for 10 s was seen): only
     * [MIN_SPAN_MS] or more of readings count, and until a new maneuver has that much the last good speed stands for
     * [GOOD_MS] (the bike did not change speed with the turn). With neither, null: no verdict.
     */
    @Synchronized
    fun kmhAt(nowMs: Long): Double? {
        val last = readings.lastOrNull() ?: return null
        if (nowMs - last.atMs > STALE_MS) return null
        val first = readings.first()
        val spanMs = last.atMs - first.atMs
        if (spanMs >= MIN_SPAN_MS) {
            val kmh = (first.meters - last.meters) * M_PER_MS_TO_KMH / spanMs
            goodKmh = kmh
            goodAtMs = nowMs
            return kmh
        }
        return goodKmh.takeIf { goodAtMs != Long.MIN_VALUE && nowMs - goodAtMs <= GOOD_MS }
    }

    /** Keeps the speed of the readings so far as the last good one, when they cover [MIN_SPAN_MS] (at [atMs], the last reading's time). */
    private fun captureGood(atMs: Long) {
        val first = readings.firstOrNull() ?: return
        val last = readings.last()
        val spanMs = last.atMs - first.atMs
        if (spanMs < MIN_SPAN_MS) return
        goodKmh = (first.meters - last.meters) * M_PER_MS_TO_KMH / spanMs
        goodAtMs = atMs
    }

    /**
     * The speed for the dash's stats text (without a hold the number blinks out between maneuvers and
     * at a stop, where the distance does not change): while a route runs, the speed last worked out stands for up to
     * [HOLD_MS] after the distance reading it came from, and a new one takes over; with none, 0 km/h. With no route there is nothing to say, so null, as before. The log line and the
     * governor keep reading [kmhAt]: an honest "unknown" there, and no frame-rate verdict from a guess.
     */
    @Synchronized
    fun displayKmhAt(nowMs: Long): Double? {
        kmhAt(nowMs)?.let {
            heldKmh = it
            heldAtMs = readings.last().atMs // the age is the distance's, not the poll's
        }
        if (!routeActive) return null
        return if (heldAtMs != Long.MIN_VALUE && nowMs - heldAtMs <= HOLD_MS) heldKmh else 0.0
    }

    companion object {
        /** How long the last speed stands when no new one can be worked out. */
        const val HOLD_MS = 3_000L

        const val SPAN_MS = 10_000L

        /** Not 2 s: with distances in steps of 100 m, 2 s of readings says 100 m in 2 s, 180 km/h. */
        const val MIN_SPAN_MS = 6_000L
        /**
         * Not 3 s: Waze's 100 m steps come 3 to 6 s apart at 60 to 120 km/h, so after 3 s the speed was gone
         * between two of them half the time, and with it the speed profile's verdict (36 tier swaps measured in
         * 16 min).
         */
        const val STALE_MS = 8_000L

        /** A fall bigger than this in one reading is no ride but a rounding or a new route (Waze's steps are 100 m under 10 km). */
        const val JUMP_METERS = 300

        /** How long the last good speed stands while a new maneuver has too few readings. */
        const val GOOD_MS = 10_000L

        /** Metres per millisecond to km/h: 1 m/ms is 1000 m/s, 3600 km/h. */
        private const val M_PER_MS_TO_KMH = 3_600.0
    }
}
