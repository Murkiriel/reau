package com.reau.app.stream

import java.util.Locale

/**
 * The speed the speed profile ([SpeedProfile]) reads. The route's estimate ([NavSpeed]) comes from how fast the distance to the next maneuver goes
 * down, in steps of 100 m, and erred 11 km/h on average against the bubble of the navigation app. The experimental switch gives it the speed of the
 * last GPS fix instead ([com.reau.app.service.GpsWatch.read]) when there is one: a route runs, and the GPS is already listened to (for the dash's info
 * ring; this never switches it on) with a fix of the last 10 s that carries a speed. Otherwise, and with the switch off, it is the route's estimate,
 * as always. Each change of source is said in the ride log (no place, only the speeds).
 */
class ProfileSpeed(private val log: (String) -> Unit = {}) {
    private var onGps: Boolean? = null

    /**
     * km/h for the profile. [on] is the switch, [routeActive] whether a route runs, [gpsMps] the GPS fix's speed in metres per second (null: the
     * GPS says none) and [routeKmh] the route's estimate (null: it has none).
     */
    @Synchronized
    fun kmh(on: Boolean, routeActive: Boolean, gpsMps: Float?, routeKmh: Double?): Double? {
        if (!on) {
            onGps = null
            return routeKmh
        }
        val gps = gpsMps?.takeIf { routeActive && it.isFinite() && it >= 0f }?.let { it * MPS_TO_KMH }
        val now = gps != null
        if (now != onGps && (now || onGps != null)) {
            val route = routeKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "none"
            log(
                if (gps != null) {
                    "speed profile: reads the GPS speed (%.0f km/h; the route says %s)".format(Locale.US, gps, route)
                } else {
                    "speed profile: back to the route's estimate (no GPS speed, or no route)"
                },
            )
        }
        onGps = now
        return gps ?: routeKmh
    }

    private companion object {
        const val MPS_TO_KMH = 3.6
    }
}
