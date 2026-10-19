package com.reau.app.aa

/**
 * Whether the dash's picture looks alive and is not: the bike moves on the route's own speed, and Android Auto, its
 * session open, has not sent a new picture for [staleAfterMs]. The compositor repeats the last picture to the dash, so
 * a frozen Android Auto looks like a normal map with nothing on the phone saying otherwise.
 *
 * Why the speed: with the bike stopped Android Auto sends nothing for up to a minute (a map that does not change), and
 * in a measured 1.27 h of riding at 15 km/h or more there was not one 5 s window without a new picture. Without a route
 * there is no speed, so no verdict (the dash shows what Android Auto last drew, as before). The silence counts from the
 * later of the last picture and the moment the bike began to move, and the speed lingers a few seconds after a stop
 * ([com.reau.app.stream.NavSpeed]), hence the 15 s. A session with no picture at all is [VideoSilence]'s.
 * [check] runs about once a second.
 */
class StaleImage(private val staleAfterMs: Long = STALE_AFTER_MS, private val movingKmh: Double = MOVING_KMH) {
    private var units = -1L
    private var unitAtMs = 0L
    private var movingSinceMs = -1L

    var isStale = false
        private set

    /** The change to tell at [nowMs] with [videoUnits] so far and the route's [kmh] (null: unknown): true = stale now, false = the picture is alive, or null. */
    fun check(nowMs: Long, videoUnits: Long, kmh: Double?): Boolean? {
        if (videoUnits != units) {
            units = videoUnits
            unitAtMs = nowMs
        }
        if (kmh != null && kmh >= movingKmh) {
            if (movingSinceMs < 0) movingSinceMs = nowMs
        } else {
            movingSinceMs = -1
        }
        val stale = videoUnits > 0 && movingSinceMs >= 0 && nowMs - maxOf(unitAtMs, movingSinceMs) >= staleAfterMs
        if (stale == isStale) return null
        isStale = stale
        return stale
    }

    companion object {
        /** No new picture for this long, moving: Android Auto's map is drawn again within a second at that speed. */
        const val STALE_AFTER_MS = 15_000L

        /** Above this the bike is moving for sure (the route's speed is an approximation of tens of km/h at times). */
        const val MOVING_KMH = 15.0
    }
}
