package com.reau.app.stream

import kotlin.math.abs

/** The verdict of a navigation-driven quality profile: full (the Settings values) or reduced (half). */
internal enum class QualityTier { FULL, REDUCED }

/**
 * The maneuver profile: near a maneuver (< [nearMeters]) is always FULL, at once; far (>= [nearMeters]) only turns
 * REDUCED once the distance has changed [hysteresisMeters] either way from where it entered the far zone
 * ([farBaseMeters]), so it does not flip back and forth right at the 1000 m edge. Either way: counting only a distance
 * that grew would keep a ride towards a far maneuver (the usual case) at FULL for good. Pure, no clock: the distance
 * itself does not drift with time, only with position.
 */
internal class ManeuverProfile(
    private val nearMeters: Int = NEAR_METERS,
    private val hysteresisMeters: Int = HYSTERESIS_METERS,
) {
    private var tier: QualityTier = QualityTier.FULL
    private var farBaseMeters: Int? = null

    /** [distanceMeters] null: no active route (or unknown) — no verdict, the state does not move. */
    fun onSample(distanceMeters: Int?): QualityTier? {
        if (distanceMeters == null) return null
        if (distanceMeters < nearMeters) {
            tier = QualityTier.FULL
            farBaseMeters = null
            return tier
        }
        val base = farBaseMeters ?: distanceMeters.also { farBaseMeters = it }
        if (tier == QualityTier.FULL && abs(distanceMeters - base) >= hysteresisMeters) tier = QualityTier.REDUCED
        return tier
    }

    companion object {
        /** Near a maneuver below this distance. */
        const val NEAR_METERS = 1000

        /** How far the distance must move in the far zone before the tier turns REDUCED. */
        const val HYSTERESIS_METERS = 200
    }
}

/**
 * The speed profile: slower, more frames; faster, fewer. A classic hysteresis band instead of the maneuver profile's
 * baseline-distance rule (which does not fit a value that wobbles like speed). [lowKmh]/[highKmh] are an estimate,
 * not a measurement.
 */
internal class SpeedProfile(
    private val lowKmh: Double = LOW_KMH,
    private val highKmh: Double = HIGH_KMH,
) {
    private var tier: QualityTier = QualityTier.FULL

    /** [kmh] null: no active route (same condition as the maneuver profile, through NavSpeed) — no verdict. */
    fun onSample(kmh: Double?): QualityTier? {
        if (kmh == null) return null
        tier = when {
            kmh <= lowKmh -> QualityTier.FULL
            kmh >= highKmh -> QualityTier.REDUCED
            else -> tier // the dead band: keeps whatever it already was
        }
        return tier
    }

    companion object {
        /** Unverified: a guess to check on the bike later, not a measurement. */
        const val LOW_KMH = 55.0

        /** Unverified: a guess to check on the bike later, not a measurement. */
        const val HIGH_KMH = 65.0
    }
}
