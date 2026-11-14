package com.reau.app.dash

import com.reau.app.dash.DashSsid.maskSsid

/**
 * Android gave up on the request by itself, well before REau's own wait ran out: it reached the dash and was refused.
 * A dash stuck mid-ride was seen refusing every join for 11 minutes after 5-12 s of a 30 s wait.
 */
private fun quickRefusal(tookMs: Long, timeoutMs: Int): Boolean = tookMs < timeoutMs / 2

/**
 * The rules of a request for the dash's network: how long it waits, whether it is tried again, and why it was refused. Pure decision logic
 * kept out of the Android-framework-calling methods of [DashWifiManager] so it is unit-testable without a real
 * ConnectivityManager/WifiManager (DashWifiManagerHelpersTest, DashStuckTest, DashJoinTest).
 */
internal object JoinRules {
    /** Why a first request for the dash's network failed, as far as REau can tell (DashStuckTest). */
    enum class Refusal(private val words: Int) {
        NOT_FOUND(com.reau.app.R.string.dash_err_ssid_not_found),
        // The dash was on this bike network a moment ago and the scan no longer sees it. The rider is told the same
        // words as NOT_FOUND; only the log tells them apart.
        GONE_AFTER_JOIN(com.reau.app.R.string.dash_err_ssid_not_found),
        SEEN_NOT_JOINED(com.reau.app.R.string.dash_err_auth_failed),
        UNKNOWN(com.reau.app.R.string.dash_err_not_found_or_password),
        // Evaluated only in the "initial connect" branch of onUnavailable and shown on the screen. A dash that hangs in the middle of a ride (the
        // mid-session branch) is [DashStuck] / [StuckDashWatch], which also raises the rider's notification (StuckDashWatchTest).
        DASH_STUCK(com.reau.app.R.string.dash_err_dash_stuck),
        ;

        /**
         * The words of this refusal and the dash's name they carry, masked: they only reach the log (the
         * name ties a log to one bike).
         */
        internal fun message(ssid: String): Pair<Int, String> = words to maskSsid(ssid)
    }

    internal const val CONNECT_TIMEOUT = 30_000  // ms — Android shows system dialog within this

    /** A reconnect (we've connected once already this session) gets the shorter timeout — see
     * the comment on [RECONNECT_TIMEOUT] for why. The first approval of a network ([firstApproval]) gets time for
     * the rider's tap on Android's dialog (DashJoin). */
    internal fun timeoutForAttempt(everConnected: Boolean, firstApproval: Boolean = false): Int =
        DashJoin.timeoutMs(everConnected, firstApproval, RECONNECT_TIMEOUT, CONNECT_TIMEOUT)

    /** Mid-session drop (dash AP went missing after we'd already connected) keeps retrying;
     * a failed initial connect / declined dialog gives up. */
    internal fun shouldRetryOnUnavailable(everConnected: Boolean, wantConnected: Boolean): Boolean =
        everConnected && wantConnected

    /** Quick refusals in a row that make a dash with no join of its own yet count as stuck. */
    internal const val STUCK_AFTER = 3

    /**
     * A dash not in the scan is not there (GONE_AFTER_JOIN when its network had already worked in this projection, so
     * the log says it went away rather than never came). A dash that refuses quickly, after its network already worked in this
     * projection (same password) or [STUCK_AFTER] times in a row, has a stuck Wi-Fi, which only a bike power-off
     * clears. Otherwise, what the scan says, as before.
     */
    internal fun refusal(seenInScan: Boolean?, tookMs: Long, timeoutMs: Int, joinedBefore: Boolean, earlyInARow: Int): Refusal =
        when {
            seenInScan == false -> if (joinedBefore) Refusal.GONE_AFTER_JOIN else Refusal.NOT_FOUND
            quickRefusal(tookMs, timeoutMs) && (joinedBefore || earlyInARow >= STUCK_AFTER) -> Refusal.DASH_STUCK
            seenInScan == true -> Refusal.SEEN_NOT_JOINED
            else -> Refusal.UNKNOWN
        }

    /** The quick refusals in a row, this one included; a request that ran its wait out starts the count over. */
    internal fun earlyInARow(previous: Int, tookMs: Long, timeoutMs: Int): Int =
        if (quickRefusal(tookMs, timeoutMs)) previous + 1 else 0

    /**
     * A reconnect scheduled by a link loss goes out only if no request went out after it was scheduled: otherwise
     * it can go out just after the projection's own request (1.4 s was seen) and cancel it half-way.
     */
    internal fun reconnectDue(wantConnected: Boolean, scheduledAt: Int, latestRequest: Int): Boolean =
        wantConnected && scheduledAt == latestRequest
}
