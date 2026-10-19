// Copied from MOTO-HUB (AGPL-3.0-only): apps/android/app/src/main/java/io/motohub/android/aa/AapNavigationStatus.kt,
// commit 725a541 (its AaNavigationGuidance.Snapshot). Changes: package, the name, and it stands on its own instead of
// inside MOTO-HUB's process-wide AaNavigationGuidance object.
package com.reau.app.aa

/**
 * The latest turn-by-turn guidance read from Android Auto's navigation status channel.
 * [maneuverType] is Android Auto's wire number (NavigationManeuver.NavigationType), -1 when none is known.
 */
data class NavGuidance(
    val active: Boolean,
    val rerouting: Boolean = false,
    val maneuverType: Int = -1,
    val roundaboutExitNumber: Int = 0,
    val road: String = "",
    val distanceToManeuverMeters: Int = -1,
    val timeToManeuverSeconds: Int = -1,
    val distanceRemainingMeters: Int = -1,
    val timeToArrivalSeconds: Long = -1L,
    val estimatedTimeAtArrival: String = "",
    /** The maneuver after the next one (the route's second step), -1 when there is none. */
    val nextManeuverType: Int = -1,
    val nextRoundaboutExit: Int = 0,
) {
    companion object {
        val INACTIVE = NavGuidance(active = false)
    }
}
