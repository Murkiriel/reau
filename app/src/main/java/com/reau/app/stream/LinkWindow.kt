package com.reau.app.stream

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the ride was like at one poll of the video loop: the bike's speed, from
 * Android Auto's route ([NavSpeed]), and the bike's Wi-Fi as Android reads it. Null where it is not known; Android
 * gives -1 for a link speed it does not know.
 */
data class LinkSample(val speedKmh: Double?, val wifiDbm: Int?, val wifiMbps: Int?)

/**
 * The speed and the Wi-Fi over one 5 s window of the stream, for its log line: the mean speed with its top, and the
 * worst signal and link the window saw (where the dash would fall behind first).
 */
internal class LinkWindow {
    private var speedSum = 0.0
    private var speeds = 0
    private var speedMax = 0.0
    private var worstDbm: Int? = null
    private var worstMbps: Int? = null

    fun add(sample: LinkSample?) {
        sample ?: return
        sample.speedKmh?.let {
            speedSum += it
            speeds++
            speedMax = maxOf(speedMax, it)
        }
        sample.wifiDbm?.let { dbm -> worstDbm = minOf(worstDbm ?: dbm, dbm) }
        sample.wifiMbps?.takeIf { it > 0 }?.let { mbps -> worstMbps = minOf(worstMbps ?: mbps, mbps) }
    }

    /** "speed~90km/h(max 102) wifi=-66dBm/26Mbps(worst)"; "speed=?" or "wifi=?" for what was not known. */
    fun text(): String {
        val speed = if (speeds == 0) "speed=?" else String.format(
            Locale.US, "speed~%dkm/h(max %d)", (speedSum / speeds).roundToInt(), speedMax.roundToInt(),
        )
        val dbm = worstDbm
        val wifi = if (dbm == null && worstMbps == null) "wifi=?" else "wifi=${dbm ?: "?"}dBm/${worstMbps ?: "?"}Mbps(worst)"
        return "$speed $wifi"
    }
}
