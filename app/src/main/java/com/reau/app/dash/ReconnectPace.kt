package com.reau.app.dash

// The pace of getting a lost bike network back: how long a request waits, the pause between requests, and how long the dash has been away.

// Once we've connected successfully this session, the system already approved this
// app+SSID pairing and won't show the dialog again — so a mid-ride reconnect attempt
// doesn't need the full 30s: a stuck request (e.g. the radio still coming back up after
// being toggled) should give up and retry sooner instead of camping on one dead request.
// Confirmed live: a reconnect attempt right as WiFi was re-enabled rode the full 30s
// timeout before the NEXT attempt (issued only 4s later) succeeded in under 3s.
internal const val RECONNECT_TIMEOUT = 10_000
internal const val RECONNECT_DELAY = 4_000L

/** How long a reconnection may go without a new request or a new loss before it counts as stuck. */
internal const val RECONNECT_WINDOW_MS: Long = RECONNECT_TIMEOUT + RECONNECT_DELAY + 2_000L

/**
 * The pause between the requests for a bike network that stays lost. A ride log of 20/10/2026 had 18 minutes (15:56 to 16:14) of the dash
 * away with a request every 14 s (10 s of request and the 4 s pause): 25 requests, 0.31 % of battery a minute, as much as streaming, for
 * a bike that was switched off. After [BACKOFF_AFTER_MS] away the pause is [BACKOFF_PAUSE_MS] (a cycle of about 45 s); a sighting of the dash
 * in the scan cuts the pause short at any time ([DashJoin.refileAfterLoss]), and a network that comes back starts the count over.
 */
internal object ReconnectPace {
    const val BACKOFF_AFTER_MS = 3 * 60_000L
    const val BACKOFF_PAUSE_MS = 35_000L

    /** The pause before the next request when the dash has been away for [awayMs] (0: it just went). */
    fun pauseMs(awayMs: Long): Long = if (awayMs >= BACKOFF_AFTER_MS) BACKOFF_PAUSE_MS else RECONNECT_DELAY
}

/**
 * How long the bike network has been lost, for [ReconnectPace]'s pause: [lost] notes the first loss (a second loss while it is still away
 * does not start the count over), [back] clears it (the network came back, or a new connection began), [pauseMs] is the pause before the
 * next request, and [backoffStarted] is true once, the first time the long pause is in force, so the log says it once.
 */
internal class AbsenceClock {
    @Volatile private var sinceMs = 0L
    @Volatile private var said = false

    fun lost(nowMs: Long) {
        if (sinceMs == 0L) sinceMs = nowMs
    }

    fun back() {
        sinceMs = 0L
        said = false
    }

    fun pauseMs(nowMs: Long): Long = ReconnectPace.pauseMs(if (sinceMs == 0L) 0L else nowMs - sinceMs)

    fun backoffStarted(nowMs: Long): Boolean {
        if (pauseMs(nowMs) == RECONNECT_DELAY || said) return false
        said = true
        return true
    }
}

/**
 * Whether the manager is in the middle of getting a lost bike network back on its own (a
 * loss after a connection, [RECONNECT_DELAY] then a request of [RECONNECT_TIMEOUT]), and recently enough to be
 * trusted: [sinceMs] is how long ago it last acted on that (the loss, or its last request). Past [RECONNECT_WINDOW_MS]
 * something is stuck and a fresh connection is the right thing. The projection's own retry joins a reconnection under
 * way instead of replacing it with a fresh connect(), which reset `everConnected` (back to the 30 s first-time wait,
 * and giving up on the next refusal) and cancelled the request already out. Pure, as the helpers of [JoinRules]
 * (DashWifiReconnectUnderWayTest).
 */
internal fun reconnectUnderWay(
    wantConnected: Boolean,
    everConnected: Boolean,
    status: WifiConnStatus,
    sinceMs: Long,
    pauseMs: Long = RECONNECT_DELAY,
): Boolean = wantConnected && everConnected && status == WifiConnStatus.REQUESTING && sinceMs in 0..(RECONNECT_TIMEOUT + pauseMs + 2_000L)
