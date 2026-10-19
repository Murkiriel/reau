// The bike network coming and going is written to the `system` line.
package com.reau.app.dash

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * Android gave up on the request by itself, well before REau's own wait ran out: it reached the dash and was refused.
 * A dash stuck mid-ride was seen refusing every join for 11 minutes after 5-12 s of a 30 s wait.
 */
private fun quickRefusal(tookMs: Long, timeoutMs: Int): Boolean = tookMs < timeoutMs / 2

// Once we've connected successfully this session, the system already approved this
// app+SSID pairing and won't show the dialog again — so a mid-ride reconnect attempt
// doesn't need the full 30s: a stuck request (e.g. the radio still coming back up after
// being toggled) should give up and retry sooner instead of camping on one dead request.
// Confirmed live: a reconnect attempt right as WiFi was re-enabled rode the full 30s
// timeout before the NEXT attempt (issued only 4s later) succeeded in under 3s.
private const val RECONNECT_TIMEOUT = 10_000
private const val RECONNECT_DELAY = 4_000L

/** How long a reconnection may go without a new request or a new loss before it counts as stuck. */
internal const val RECONNECT_WINDOW_MS: Long = RECONNECT_TIMEOUT + RECONNECT_DELAY + 2_000L

/**
 * Whether the manager is in the middle of getting a lost bike network back on its own (a
 * loss after a connection, [RECONNECT_DELAY] then a request of [RECONNECT_TIMEOUT]), and recently enough to be
 * trusted: [sinceMs] is how long ago it last acted on that (the loss, or its last request). Past [RECONNECT_WINDOW_MS]
 * something is stuck and a fresh connection is the right thing. The projection's own retry joins a reconnection under
 * way instead of replacing it with a fresh connect(), which reset `everConnected` (back to the 30 s first-time wait,
 * and giving up on the next refusal) and cancelled the request already out. Pure, as the companion's helpers
 * (DashWifiReconnectUnderWayTest).
 */
internal fun reconnectUnderWay(wantConnected: Boolean, everConnected: Boolean, status: WifiConnStatus, sinceMs: Long): Boolean =
    wantConnected && everConnected && status == WifiConnStatus.REQUESTING && sinceMs in 0..RECONNECT_WINDOW_MS

/** One network of the last scan, as far as the log needs it ([timestampUs]: microseconds since boot, as ScanResult has it). */
internal data class ScanSeen(val ssid: String, val level: Int, val frequency: Int, val timestampUs: Long)

/**
 * What the last scan says about the dash, for the ride log only (no decision reads it): how many networks, and for
 * the dash its signal, band and how old that sighting is. A request the system's dialog keeps "searching" for,
 * while `seenInScan` is true, may be living on a cached scan that is minutes old; this line tells. [nowUs] in the
 * same microseconds since boot.
 */
internal fun scanEvidence(seen: List<ScanSeen>?, pendingSsid: String, pendingPrefix: Boolean, nowUs: Long): String {
    if (seen == null) return "scan unreadable"
    if (seen.isEmpty()) return "scan empty"
    val dash = seen.filter { DashWifiManager.matchesPendingSsid(it.ssid, pendingSsid, pendingPrefix) }
    if (dash.isEmpty()) return "scan of ${seen.size} networks, the dash not among them"
    return dash.joinToString(prefix = "scan of ${seen.size} networks, the dash: ", separator = "; ") {
        val age = ((nowUs - it.timestampUs) / MICROS_PER_SECOND).coerceAtLeast(0)
        "'${DashWifiManager.maskSsid(it.ssid)}' ${it.level} dBm ${it.frequency} MHz seen $age s ago"
    }
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val MICROS_PER_MILLI = 1_000L

/**
 * Programmatically connects to the dash's WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * bindProcessToNetwork() routes all UDP/TCP from this process through
 * the dash's network so packets reach 192.168.1.1 even if the phone
 * has another network (cellular) available.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Which network the rider already let REau join; null (a scan-only manager, a test) assumes nothing about it. */
    private val joinMemory: JoinMemory? = null,
) {
    /** Why a first request for the dash's network failed, as far as REau can tell (DashStuckTest). */
    enum class Refusal(private val words: Int) {
        NOT_FOUND(com.reau.app.R.string.dash_err_ssid_not_found),
        // The dash was on this bike network a moment ago and the scan no longer sees it. The rider is told the same
        // words as NOT_FOUND; only the log tells them apart.
        GONE_AFTER_JOIN(com.reau.app.R.string.dash_err_ssid_not_found),
        SEEN_NOT_JOINED(com.reau.app.R.string.dash_err_auth_failed),
        UNKNOWN(com.reau.app.R.string.dash_err_not_found_or_password),
        DASH_STUCK(com.reau.app.R.string.dash_err_dash_stuck),
        ;

        /**
         * The words of this refusal and the dash's name they carry, masked: they only reach the log (the
         * name ties a log to one bike).
         */
        internal fun message(ssid: String): Pair<Int, String> = words to maskSsid(ssid)
    }

    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 30_000  // ms — Android shows system dialog within this
        // RECONNECT_TIMEOUT and RECONNECT_DELAY are at the top of the file (the reconnection rule there reads them).
        // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
        private const val WifiManagerUnknownSsid = "<unknown ssid>"

        // Pure decision logic pulled out of the Android-framework-calling methods below so it's
        // unit-testable without a real ConnectivityManager/WifiManager — see DashWifiManagerHelpersTest.

        /** A reconnect (we've connected once already this session) gets the shorter timeout — see
         * the class doc on RECONNECT_TIMEOUT for why. The first approval of a network ([firstApproval]) gets time for
         * the rider's tap on Android's dialog (DashJoin). */
        internal fun timeoutForAttempt(everConnected: Boolean, firstApproval: Boolean = false): Int =
            DashJoin.timeoutMs(everConnected, firstApproval, RECONNECT_TIMEOUT, CONNECT_TIMEOUT)

        /** Strips the quotes Android wraps SSIDs in and collapses the "can't read it" sentinel to
         * blank, so callers can treat "blank" as the one signal for "no usable SSID". */
        internal fun normalizeSsid(raw: String?): String =
            raw.orEmpty().trim('"').let { if (it == WifiManagerUnknownSsid) "" else it }

        /** Mid-session drop (dash AP went missing after we'd already connected) keeps retrying;
         * a failed initial connect / declined dialog gives up. */
        internal fun shouldRetryOnUnavailable(everConnected: Boolean, wantConnected: Boolean): Boolean =
            everConnected && wantConnected

        /** Does [ssid] match what we're trying to connect to — exact or prefix, per [pendingPrefix]? */
        /**
         * Whether the last scan shows the dash: true, false, or null when the scan says nothing. Android
         * hands an app in the background an EMPTY scan instead of refusing, and an empty scan was read as "the dash is
         * not nearby" with the bike beside the phone; somewhere with no network at all is rare enough to be unknown.
         */
        internal fun scanVerdict(ssids: List<String>?, pendingSsid: String, pendingPrefix: Boolean): Boolean? =
            ssids?.takeIf { it.isNotEmpty() }?.any { matchesPendingSsid(it, pendingSsid, pendingPrefix) }

        internal fun matchesPendingSsid(ssid: String, pendingSsid: String, pendingPrefix: Boolean): Boolean =
            if (pendingPrefix) ssid.startsWith(pendingSsid) else ssid == pendingSsid

        /** The actual decision inside [findAlreadyConnectedDashNetwork]'s `cm.allNetworks` scan,
         * pulled out so it's unit-testable without a real ConnectivityManager — everything else in
         * that function is framework enumeration, this is the one real judgment call: a candidate
         * network with no readable SSID (some OEMs hide it without location permission) is accepted
         * as a best-effort match; one with a readable SSID must equal [activeSsid] exactly. */
        internal fun candidateMatchesActive(candidateSsid: String?, activeSsid: String): Boolean =
            candidateSsid == null || candidateSsid == activeSsid

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

        /** Redacts an SSID for logs: only the shape survives, not the value — the dash's SSID
         * ties a log line to a specific bike/rider, so exported diagnostics (Settings → Export
         * logs) shouldn't carry it in the clear: such logs get pulled/shared far more casually
         * than expected. */
        internal fun maskSsid(ssid: String): String {
            if (ssid.isBlank()) return ""
            if (ssid.length <= 4) return ssid.take(2) + "**"
            return ssid.take(3) + "****" + ssid.takeLast(2)
        }
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Is the phone's Wi‑Fi radio on? WifiNetworkSpecifier can only associate to the dash when
     * Wi‑Fi is enabled, and on Android 10+ the app can't enable it itself — so we check up front
     * and have the UI send the rider to the Wi‑Fi settings panel rather than silently timing out.
     * (Doesn't matter which network Wi‑Fi is currently joined to — the specifier connects the dash
     * as a separate per-app network.)
     */
    fun isWifiEnabled(): Boolean = wifi.isWifiEnabled

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork:
     * that routed the WHOLE app through the dash's no-internet WiFi, so routing,
     * geocoding and shared-link resolution all failed while connected ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    // True once we've successfully connected this session. Lets us tell a MID-RIDE drop (keep
    // retrying — the dash AP may have hung and will return after a power-cycle) apart from a failed
    // INITIAL connect / user-declined dialog (give up, don't spam).
    private var everConnected = false
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null
    private var requestStartMs = 0L
    // What the request would wait without the stretch of a first approval: the yardstick of a "quick" refusal.
    private var requestTimeoutMs = CONNECT_TIMEOUT
    // The last request asked for a network the rider had not approved yet (the system's dialog is expected).
    private var requestFirstApproval = false
    // When this manager last acted on getting a lost network back (the loss, the refusal, its own request).
    @Volatile private var reconnectActedMs = 0L
    // Requests made so far; a scheduled reconnect remembers the count it was scheduled at (reconnectDue).
    private var requests = 0
    // The dash's network worked at least once since this manager was made (one projection), and the quick refusals
    // in a row since it last worked (refusal, earlyInARow).
    private var joinedOnce = false
    private var quickRefusals = 0
    // When the first request of this manager went out, and when the last one was let go without the system answering it:
    // only for the log, to see a request that the system leaves "searching" while REau re-issues it.
    private var firstRequestMs = 0L
    private var releasedUnansweredMs = 0L

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    /** Told each time the bike's network is got (after [state] says CONNECTED); the projection tries a waiting dash at once. */
    @Volatile var onNetworkAvailable: (() -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog.
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes the app work on
     *   any rider's dash without hardcoding their SSID. When false, exact-match.
     */
    fun connect(ssid: String, password: String = "", prefixMatch: Boolean = false) {
        wantConnected    = true
        everConnected    = false   // fresh user-initiated attempt
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        requestNetwork()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught") // a failed scan lookup is logged and means no SSID
    fun findDashSsid(prefix: String): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.scanResults
            .mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } }
            .firstOrNull { it.startsWith(prefix) }
            .also {
                val masked = it?.let(::maskSsid) ?: "not found"
                Log.i(TAG, "Scan lookup for '${maskSsid(prefix)}*' → $masked")
                // Happens BEFORE any connect attempt is logged -- if the dash is never found here,
                // nothing else explains why connect() never even started.
                com.reau.app.data.RideDiagnostics.log("wifi", "scan lookup for '${maskSsid(prefix)}*' → $masked")
            }
    } catch (e: Exception) {
        Log.w(TAG, "Scan lookup failed: ${e.message}")
        com.reau.app.data.RideDiagnostics.warn("wifi", "scan lookup failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Whether the last Wi-Fi scan has [ssid] (or, with [prefix], a network starting with it) seen recently
     * ([DashJoin.SIGHTING_FRESH_MS]: the scan list keeps a dash that was switched off for minutes). False when it does not or
     * when the scan cannot be read or is empty: the caller only acts on a sighting.
     */
    @SuppressLint("MissingPermission")
    fun seenInLastScan(ssid: String, prefix: Boolean): Boolean {
        val seen = runCatching {
            wifi.scanResults.mapNotNull { r ->
                r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { ScanSeen(it, r.level, r.frequency, r.timestamp) }
            }
        }.getOrNull()
        return DashJoin.seenRecently(seen, ssid, prefix, SystemClock.elapsedRealtime() * MICROS_PER_MILLI)
    }

    /**
     * True while this manager is getting a lost bike network back by itself, recently enough to be trusted
     * (the rule is the top-level [reconnectUnderWay]). [WifiDashLinkUp] then waits for that instead of asking anew.
     */
    fun reconnectUnderWay(nowMs: Long = System.currentTimeMillis()): Boolean =
        reconnectUnderWay(wantConnected, everConnected, _state.value.status, nowMs - reconnectActedMs)

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        // Asked twice by the projection's stop and release; only a request with something to let go is told.
        if (disconnectHasSomethingToLetGo(wantConnected, networkCallback != null, _state.value.status)) {
            com.reau.app.data.RideDiagnostics.log("wifi", "disconnect requested")
        }
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught") // a failed request is logged and shown as the ERROR state
    private fun requestNetwork() {
        release()
        requests++
        requestStartMs = System.currentTimeMillis()
        reconnectActedMs = requestStartMs
        val timeout = chooseTimeout()
        Log.i(TAG, "Requesting WiFi: '${maskSsid(pendingSsid)}' " +
            "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"}, timeout=${timeout}ms" +
            "${if (requestFirstApproval) ", first approval" else ""})")
        logRequestContext(timeout)
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        // Some OEM builds (seen on Android 11) suppress the WifiNetworkSpecifier system dialog
        // entirely when the phone is ALREADY joined to the exact network being requested —
        // requestNetwork() then just times out instead of returning it. Reusing an already-
        // connected match sidesteps that: no new dialog, no request, straight to CONNECTED.
        findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
            network = activeNetwork
            resolvedSsid = activeSsid
            everConnected = true
            joinedOnce = true
            quickRefusals = 0
            // Not remembered as approved: a network the phone joined by itself (a suggestion, a saved one) says nothing
            // about the dialog of a request for it.
            Log.i(TAG, "Using already-connected matching WiFi '${maskSsid(activeSsid)}'")
            com.reau.app.data.RideDiagnostics.log("wifi", "using already-connected WiFi '${maskSsid(activeSsid)}'")
            watchJoined(activeNetwork)
            onSsidResolved?.invoke(activeSsid)
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
            return
        }

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) {
            // The builder throws for a passphrase it cannot take (not ASCII, as a pasted non-breaking space is);
            // uncaught, that killed the worker thread and the app. Reported like any other failed request.
            try {
                specBuilder.setWpa2Passphrase(pendingPassword)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "the dash password was refused: ${e.message}", e)
                com.reau.app.data.RideDiagnostics.error("wifi", passphraseRefusedLine(e))
                _state.value = WifiState(status = WifiConnStatus.ERROR, ssid = pendingSsid, error = "${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The dash hotspot has no WAN — it never gets NET_CAPABILITY_INTERNET. Requesting
            // it anyway (the default) leaves this network's UID exemption from Restricted
            // Networking Mode / per-app firewall rules improperly registered by netd, since the
            // request and the actual network never agree on capabilities — that's what produced
            // "Binding socket to network N failed: EPERM" on a device with Restricted Networking
            // Mode on, even with no VPN routing involved. Local-only WifiNetworkSpecifier
            // requests are documented to need this removed for exactly this reason.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        // Android 12+: FLAG_INCLUDE_LOCATION_INFO, so the joined network's name is not redacted.
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BikeCallback(DashName.callbackFlags()) else BikeCallback()

        networkCallback = cb
        try {
            cm.requestNetwork(request, cb, timeout)
            startAndroid11SsidPolling()
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork threw: ${e.message}", e)
            com.reau.app.data.RideDiagnostics.error("wifi", "requestNetwork threw: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val scheduledAt = requests
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (reconnectDue(wantConnected, scheduledAt, requests)) requestNetwork()
        }
    }

    /** Read the connected network's SSID (strips the surrounding quotes Android adds). */
    private fun resolveSsid(network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val info = caps.transportInfo as? WifiInfo ?: return ""
        return normalizeSsid(info.ssid)
    }

    /**
     * Log only, for a request the system's dialog leaves "searching": which request this is and how long REau has been at
     * it, how long since the last one was let go unanswered, whether the screen is on and unlocked (the system's dialog
     * needs it) and what the scan says of the dash, with the age of that sighting.
     */
    @SuppressLint("MissingPermission")
    private fun logRequestContext(timeout: Int) {
        if (firstRequestMs == 0L) firstRequestMs = requestStartMs
        val now = SystemClock.elapsedRealtime()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val sinceFirst = (System.currentTimeMillis() - firstRequestMs) / 1_000
        val gap = if (releasedUnansweredMs == 0L) "first" else "${now - releasedUnansweredMs} ms after the last was let go unanswered"
        val line = "requestNetwork '${maskSsid(pendingSsid)}' everConnected=$everConnected timeout=${timeout}ms; " +
            "request #$requests (${sinceFirst}s since the first, $gap), screen interactive=${power?.isInteractive} " +
            "locked=${keyguard?.isKeyguardLocked}, ${lastScanEvidence()}, " +
            "approvedBefore=${approvedBefore()}"
        Log.i(TAG, line)
        com.reau.app.data.RideDiagnostics.log("wifi", line)
    }

    /** Log only: what the last Wi-Fi scan says of the dash, and how old that sighting is. */
    @SuppressLint("MissingPermission")
    private fun lastScanEvidence(): String {
        val seen = runCatching {
            wifi.scanResults.mapNotNull { r ->
                r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { ScanSeen(it, r.level, r.frequency, r.timestamp) }
            }
        }.getOrNull()
        return scanEvidence(seen, pendingSsid, pendingPrefix, SystemClock.elapsedRealtime() * MICROS_PER_MILLI)
    }

    /** How long the request about to go out waits: more for a network the rider has not approved yet (DashJoin). */
    private fun chooseTimeout(): Int {
        requestFirstApproval = !everConnected && needsApproval()
        requestTimeoutMs = timeoutForAttempt(everConnected)
        return timeoutForAttempt(everConnected, requestFirstApproval)
    }

    /** The request about to go out is the first for a network the rider has not approved (the system's dialog is expected). */
    private fun needsApproval(): Boolean = runCatching {
        DashJoin.needsApproval(joinMemory?.joinedSsid, pendingSsid, pendingPrefix)
    }.getOrDefault(false)

    /** Log only: whether the system had already been told to let REau join this network ("unknown" without a memory). */
    private fun approvedBefore(): String = if (joinMemory == null) "unknown" else (!needsApproval()).toString()

    /** The network was joined: remembered, so that the next request for it waits as usual (a keystore failure costs nothing). */
    private fun rememberJoined(name: String) {
        val memory = joinMemory ?: return
        if (!DashJoin.worthKeeping(name, pendingSsid, pendingPrefix)) return
        runCatching { if (memory.joinedSsid != name) memory.joinedSsid = name }
            .onFailure { com.reau.app.data.RideDiagnostics.warn("wifi", "could not remember the joined network: ${it.javaClass.simpleName}") }
    }

    private fun release() {
        if (networkCallback != null) {
            val status = _state.value.status
            val unanswered = status == WifiConnStatus.REQUESTING
            com.reau.app.data.RideDiagnostics.log(
                "wifi", "released request #$requests after ${System.currentTimeMillis() - requestStartMs} ms (status=$status, unanswered=$unanswered)",
            )
            // A request let go while the system had not answered it: the next one starts the system's dialog over.
            if (unanswered) releasedUnansweredMs = SystemClock.elapsedRealtime()
        }
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }

    // ── Android <12 SSID-redaction fallback ─────────────────────────────────
    // On some Android 11 / early-12 OEM builds, NetworkCallback.onCapabilitiesChanged never
    // delivers a readable WifiInfo.transportInfo for a WifiNetworkSpecifier-requested network —
    // the connection itself succeeds (onAvailable fires) but the SSID we need for the K1G
    // handshake never resolves (seen on real Android 11
    // hardware). Below API 31 (S), poll the legacy WifiManager.connectionInfo (deprecated but
    // still populated) a few times as a fallback; API 31+ already gets the SSID reliably via
    // onCapabilitiesChanged and doesn't need this.

    private fun startAndroid11SsidPolling() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) return
        scope.launch {
            delay(5_000)
            repeat(6) {
                if (_state.value.status == WifiConnStatus.CONNECTED && resolvedSsid != null) return@launch
                findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
                    network = activeNetwork
                    resolvedSsid = activeSsid
                    Log.i(TAG, "Android 11 SSID fallback resolved '${maskSsid(activeSsid)}'")
                    com.reau.app.data.RideDiagnostics.log("wifi", "Android 11 SSID fallback resolved '${maskSsid(activeSsid)}'")
                    onSsidResolved?.invoke(activeSsid)
                    _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
                    return@launch
                }
                delay(2_000)
            }
        }
    }

    /** The currently-associated WiFi network, IF its SSID matches [pendingSsid]/[pendingPrefix] —
     * lets [requestNetwork] reuse a connection the system already made instead of opening a new,
     * possibly-redundant request. */
    @SuppressLint("MissingPermission")
    private fun findAlreadyConnectedDashNetwork(): Pair<Network, String>? {
        val activeSsid = readActiveWifiSsid()?.takeIf { matchesPendingSsid(it, pendingSsid, pendingPrefix) } ?: return null
        val wifiNetwork = cm.allNetworks.firstOrNull { candidate ->
            val caps = cm.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val candidateSsid = normalizeSsid((caps.transportInfo as? WifiInfo)?.ssid).ifBlank { null }
            candidateMatchesActive(candidateSsid, activeSsid)
        } ?: return null
        return wifiNetwork to activeSsid
    }

    @SuppressLint("MissingPermission")
    private fun readActiveWifiSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        normalizeSsid(wifi.connectionInfo?.ssid).ifBlank { null }
    }.getOrNull()

    /** Does the pending SSID show up in the latest scan? Same scan API [findDashSsid] already
     * uses — reused here only to disambiguate the final give-up message in [requestNetwork]'s
     * `onUnavailable()`. Null (not false) if the scan itself couldn't be read (e.g. no location
     * permission) — a caller must NOT treat "couldn't check" the same as "confirmed absent". */
    @SuppressLint("MissingPermission")
    private fun pendingSsidSeenInScan(): Boolean? = scanVerdict(
        runCatching { wifi.scanResults.mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } } }.getOrNull(),
        pendingSsid, pendingPrefix,
    )

    /** The bike Wi-Fi request's callback; on Android 12+ built with [DashName.callbackFlags] (see the call site). */
    private inner class BikeCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onAvailable(network: Network) {
            this@DashWifiManager.network = network
            val tookMs = System.currentTimeMillis() - requestStartMs
            everConnected = true
            joinedOnce = true
            quickRefusals = 0
            reconnectJob?.cancel()
            // SSID is read in onCapabilitiesChanged (transportInfo isn't populated here
            // yet on Android 13+). Try once anyway, else fall back to the pending value.
            val resolved = resolveSsid(network).takeIf { it.isNotBlank() } ?: pendingSsid
            rememberJoined(resolved)
            Log.i(TAG, "WiFi connected ✓  ssid='${maskSsid(resolved)}' network=$network (${tookMs}ms)")
            com.reau.app.data.RideDiagnostics.log("wifi", "connected '${maskSsid(resolved)}' in ${tookMs}ms")
            com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(true))
            if (resolved.isNotBlank() && resolved != pendingSsid) onSsidResolved?.invoke(resolved)
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
            onNetworkAvailable?.invoke()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Canonical place to read the connected SSID — REQUIRED for auth, since the
            // dash validates the SSID inside the encrypted handshake (DashAuth). Without
            // the real SSID (prefix-discovery), every auth is rejected.
            val info = caps.transportInfo as? WifiInfo ?: return
            val ssid = normalizeSsid(info.ssid)
            if (ssid.isBlank()) return
            if (ssid == resolvedSsid) return
            resolvedSsid = ssid
            rememberJoined(ssid)
            this@DashWifiManager.network = network
            Log.i(TAG, "Resolved dash SSID: '${maskSsid(ssid)}'")
            com.reau.app.data.RideDiagnostics.log("wifi", "resolved SSID via onCapabilitiesChanged: '${maskSsid(ssid)}'")
            onSsidResolved?.invoke(ssid)
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
        }

        override fun onUnavailable() {
            this@DashWifiManager.network = null
            val tookMs = System.currentTimeMillis() - requestStartMs
            // Mid-ride drop where the dash AP went missing (we HAD connected): keep retrying so a
            // dash that's hung — and revived by a bike power-cycle — auto-rejoins with no manual
            // step. The ViewModel's reconnect-giveup timer still bounds this if it never returns.
            if (shouldRetryOnUnavailable(everConnected, wantConnected)) {
                Log.w(TAG, "WiFi unavailable mid-session (${tookMs}ms) — dash AP missing, retrying in ${RECONNECT_DELAY}ms")
                com.reau.app.data.RideDiagnostics.warn(
                    "wifi", "unavailable after ${tookMs}ms (mid-session) — retrying in ${RECONNECT_DELAY}ms; ${lastScanEvidence()}, " +
                        "approvedBefore=${approvedBefore()}",
                )
                _state.value = WifiState(
                    status = WifiConnStatus.REQUESTING,
                    ssid   = pendingSsid,
                    error  = context.getString(com.reau.app.R.string.dash_err_wifi_lost),
                )
                reconnectActedMs = System.currentTimeMillis()
                scheduleReconnect()
                return
            }
            // Failed INITIAL connect / user declined the dialog → give up. onUnavailable()
            // alone can't tell WHY (Android collapses "SSID not in range" and "auth/password
            // rejected" into the same callback) — but findDashSsid() already proved a fresh
            // scan can see the dash's SSID, so reuse that same lookup here to disambiguate the
            // message instead of leaving the rider guessing (a real gap seen in a log:
            // several consecutive failures with no way to tell if the dash was just
            // out of range or the saved password had gone stale).
            // A quick refusal after the network worked, or several in a row, is a stuck dash (DashStuckTest).
            val seenInScan = pendingSsidSeenInScan()
            quickRefusals = earlyInARow(quickRefusals, tookMs, requestTimeoutMs)
            val refusal = refusal(seenInScan, tookMs, requestTimeoutMs, joinedOnce, quickRefusals)
            val message = refusal.message(pendingSsid).let { (text, name) -> context.getString(text, name) }
            Log.w(TAG, "WiFi unavailable (${tookMs}ms) — seenInScan=$seenInScan refusal=$refusal")
            com.reau.app.data.RideDiagnostics.warn(
                "wifi", "unavailable after ${tookMs}ms (initial connect) — giving up, seenInScan=$seenInScan " +
                    "quick=$quickRefusals joined=$joinedOnce refusal=$refusal firstApproval=$requestFirstApproval " +
                    "approvedBefore=${approvedBefore()}; ${lastScanEvidence()}",
            )
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = message,
                dashVisible = refusal == Refusal.DASH_STUCK || refusal == Refusal.SEEN_NOT_JOINED,
            )
            wantConnected = false
        }

        override fun onLost(network: Network) = bikeLost()
    }

    /** The log line for a passphrase Android refused: whatever Android says, the password itself is not in it. */
    private fun passphraseRefusedLine(e: IllegalArgumentException): String =
        "the dash password was refused by Android: ${e.javaClass.simpleName}: ${e.message?.replace(pendingPassword, "…")}"

    /** The bike network went: forgotten, and asked for again while the rider still wants the dash. */
    private fun bikeLost() {
        Log.w(TAG, "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms")
        com.reau.app.data.RideDiagnostics.warn("wifi", "link lost — reconnecting in ${RECONNECT_DELAY}ms")
        com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(false))
        network = null
        _state.value = WifiState(
            status = WifiConnStatus.REQUESTING,
            ssid   = pendingSsid,
            error  = context.getString(com.reau.app.R.string.dash_err_link_lost),
        )
        reconnectActedMs = System.currentTimeMillis()
        if (wantConnected) scheduleReconnect()
    }

    /**
     * A dash network the phone had joined by itself is used without a request of REau's own, so no
     * request callback says when it goes; without this, every try bound to the same dead network (EPERM) for minutes.
     * A listener for Wi-Fi networks tells its loss; any other network it hears of is none of its business.
     */
    // registerNetworkCallback throws a TooManyRequestsException, hidden from the SDK (a RuntimeException), when the
    // app holds too many callbacks; a watcher that could not be set must never cost the connection.
    @Suppress("TooGenericExceptionCaught")
    private fun watchJoined(joined: Network) {
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost == joined && network == joined) bikeLost()
            }
        }
        val anyWifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(anyWifi, watcher)
            networkCallback = watcher // release() lets it go with the rest
        } catch (e: RuntimeException) {
            com.reau.app.data.RideDiagnostics.warn("wifi", "could not watch the joined WiFi: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
