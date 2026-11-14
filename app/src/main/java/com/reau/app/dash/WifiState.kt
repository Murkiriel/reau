package com.reau.app.dash

// The state of the bike's Wi-Fi as the rest of the app sees it, and the two rules of letting a request go that the ride log tells.

enum class WifiConnStatus { IDLE, REQUESTING, CONNECTED, ERROR }

/**
 * Whether a disconnect request lets go of anything (the rider's want, a request on Android, a state); else it is not
 * told.
 */
internal fun disconnectHasSomethingToLetGo(wantConnected: Boolean, hasRequest: Boolean, status: WifiConnStatus): Boolean =
    wantConnected || hasRequest || status != WifiConnStatus.IDLE

data class WifiState(
    val status: WifiConnStatus = WifiConnStatus.IDLE,
    val ssid: String = "",
    val error: String? = null,
    /** The dash is in Android's scan but refuses the join (stuck, or refusing): worth trying again sooner. */
    val dashVisible: Boolean = false,
)

/**
 * The line for a request let go. [answered]: the system gave the network at some point (even if it was lost since, as the
 * 357 720 ms of a ride); only a request that never was is "let go unanswered". It used to read the state, which a loss had
 * already put back to REQUESTING.
 */
internal fun releaseLine(requestNo: Int, tookMs: Long, status: WifiConnStatus, answered: Boolean): String =
    "released request #$requestNo after $tookMs ms (status=$status, answered=$answered, unanswered=${!answered})"
