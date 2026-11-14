// The bike network coming and going is written to the `system` line.
package com.reau.app.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
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
import com.reau.app.dash.DashSsid.candidateMatchesActive
import com.reau.app.dash.DashSsid.maskSsid
import com.reau.app.dash.DashSsid.matchesPendingSsid
import com.reau.app.dash.DashSsid.normalizeSsid
import com.reau.app.dash.DashSsid.scanVerdict
import com.reau.app.dash.JoinRules.CONNECT_TIMEOUT
import com.reau.app.dash.JoinRules.Refusal
import com.reau.app.dash.JoinRules.earlyInARow
import com.reau.app.dash.JoinRules.reconnectDue
import com.reau.app.dash.JoinRules.refusal
import com.reau.app.dash.JoinRules.shouldRetryOnUnavailable
import com.reau.app.dash.JoinRules.timeoutForAttempt
import com.reau.app.service.ProjectionService
import com.reau.app.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How often an unanswered request looks at the scan for the dash ([DashWifiManager.watchForTheDash]). */
private const val WATCH_PERIOD_MS = 2_000L

/** The scan lookups of every manager in the process: one at a time, and not again within a second (the managers of the projection and of the screen). */
private val dashSearches = RecentSearch<DashFound>()

/** What a scan lookup found: the name and whether the dash was seen recently (the scan list keeps a bike that left for minutes). */
private data class DashFound(val name: String?, val fresh: Boolean)
private const val MICROS_PER_MILLI = 1_000L
private const val MS_PER_SECOND = 1_000L

/**
 * Programmatically connects to the dash's WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * The dash's network is not made the process's default (no bindProcessToNetwork: that took routing, geocoding and
 * link-sharing off the phone's internet). Each dash socket is bound to it on its own (Network.bindSocket), so the packets
 * reach 192.168.1.1 while everything else keeps the phone's other network (cellular).
 *
 * Auto-reconnects on link loss until disconnect() is called.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Which network the rider already let REau join; null (a scan-only manager, a test) assumes nothing about it. */
    private val joinMemory: JoinMemory? = null,
    /** The experimental switch of the band ([BandChoice]), read at each request; off (the default), no request names a band. */
    private val band24: () -> Boolean = { false },
) {
    private val bandChoice = BandChoice()

    companion object {
        private const val TAG              = "DashWifiManager"
        /** WifiInfo's own "no signal reading" (INVALID_RSSI is hidden from the public SDK). */
        private const val NO_RSSI          = -127
        /** How many refused networks are remembered (they are few: one per refusal, cleared when the rider switches off). */
        private const val MAX_REFUSED      = 8
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

    // The looks of the watches at the Wi-Fi list that went to the log (ScanLookLog).
    private val scanLooks = ScanLookLog()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork:
     * that routed the WHOLE app through the dash's no-internet WiFi, so routing,
     * geocoding and shared-link resolution all failed while connected ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // The callback is still registered with Android: false once the system dropped it itself (onUnavailable), when unregistering
    // it again only makes Android log "NetworkCallback was already unregistered".
    @Volatile private var callbackRegistered = false
    // The current request was answered by the system (the network came, even if it was lost since): what "let go unanswered" means.
    @Volatile private var requestAnswered = false
    @Volatile private var reconnectJob: Job? = null
    // True once we've successfully connected this session. Lets us tell a MID-RIDE drop (keep
    // retrying — the dash AP may have hung and will return after a power-cycle) apart from a failed
    // INITIAL connect / user-declined dialog (give up, don't spam).
    private var everConnected = false
    // Read by the system's callback threads and the coroutines, written by the rider's connect() and disconnect().
    @Volatile private var wantConnected = false
    // Held by disconnect() and by every request: a request that was due when the rider switched off cannot go out after the
    // switch-off and be left asking for the bike's Wi-Fi until the process dies (audit of 20/10/2026).
    private val requestLock = Any()
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
    // What the phone knew of the network that is up (the line of its loss), and the channel of the connection that was lost,
    // to say on which channel the dash came back (LinkLoss).
    @Volatile private var netFacts: NetFacts? = null
    @Volatile private var lostMhz: Int? = null
    // How many times a first connection's request was put out again because the dash was in the scan and had not joined (DashJoin.refileSightedAndSlow).
    @Volatile private var sightedRefiles = 0
    // When the bike network was lost and has not come back since (0: it is there), for the pause between requests (ReconnectPace); said once.
    private val absence = AbsenceClock()
    // The network in use is one the phone had joined by itself (a suggestion, a saved one), not the answer to REau's own request.
    @Volatile private var joinedByPhone = false
    // The tries that found the dash's network unavailable since it was last there (for the line that is not written at every try).
    private var unavailableInARow = 0
    @Volatile private var awaitingChannel = false
    // The loss being got back (microseconds since boot, as the scan's stamps; 0 = none), the watch over it and its last refiling.
    @Volatile private var lostAtUs = 0L
    @Volatile private var lossWatch: Job? = null
    private var lastRefileMs = 0L
    // Sockets refused by Android (EPERM) in a row, and the networks it was refused on: they are not used again as "already connected".
    private val bindRefusals = BindRefusals()
    // Which refusal the projection's "try now" after [RefusalRetry.AFTER_MS] belongs to; another refusal or a switch-off makes it stale.
    private var refusalSerial = 0
    private val refusedNetworks = ArrayList<Network>()
    // The pause after a refusal that is not asked again at once (BindRefusals), added to the pace of the requests; 0 when there is none.
    @Volatile private var holdOffMs = 0L
    // The dash that is in the scan and refuses every join (its Wi-Fi hung), told to the rider once for an absence (DashStuck).
    private val stuck = StuckDashWatch()

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    /** Told each time the bike's network is got (after [state] says CONNECTED); the projection tries a waiting dash at once. */
    @Volatile var onNetworkAvailable: (() -> Unit)? = null

    /**
     * Told `true` when the dash is judged hung (it is in the scan with a good signal and refuses every join, [DashStuck]) and `false` when
     * that is over (the dash is back, or the rider switched off): the service shows or takes away the rider's notice.
     */
    @Volatile var onDashStuck: ((Boolean) -> Unit)? = null

    /**
     * Told `true` when Android refused the sockets of the dash's network [BindRefusals.TELL_AT] times in a row (EPERM) and `false` when that is
     * over (a bind worked, or the rider switched off): the service shows or takes away the rider's notice.
     */
    var onBindRefused: ((Boolean) -> Unit)? by bindRefusals::onTold

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
        netFacts = null
        lostMhz = null
        awaitingChannel = false
        unavailableInARow = 0
        sightedRefiles = 0
        holdOffMs = 0L
        absence.back()
        DashHeard.forget()
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
    fun findDashSsid(prefix: String): String? =
        dashSearches.get(prefix, after = { if (it.fresh) DashSighting.sighted() }) { searchDashSsid(prefix) }.name

    @SuppressLint("MissingPermission")
    // ScanResult.SSID is deprecated: the WifiSsid replacing it needs Android 13, and this app starts at Android 10 (minSdk 29)
    @Suppress("TooGenericExceptionCaught", "DEPRECATION") // a failed scan lookup is logged and means no SSID
    private fun searchDashSsid(prefix: String): DashFound = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val seen = wifi.scanResults.mapNotNull { r ->
            r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { ScanSeen(it, r.level, r.frequency, r.timestamp) }
        }
        // With several dashes in sight (a meet, a parking lot) the one with the best recent signal is taken, not the first listed.
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        ScanHistory.trail.note(seen.size, SystemClock.elapsedRealtime(), newestStamp(seen))
        val choice = DashPick.strongest(seen, prefix, nowUs)
        val said = choice?.describe() ?: "not found"
        Log.i(TAG, "Scan lookup for '${maskSsid(prefix)}*' → $said")
        // Happens BEFORE any connect attempt is logged -- if the dash is never found here,
        // nothing else explains why connect() never even started.
        com.reau.app.data.RideDiagnostics.log("wifi", "scan lookup for '${maskSsid(prefix)}*' → $said")
        // Told by findDashSsid, outside the search's lock, and only for a dash seen recently: an old entry of the scan list is no news.
        DashFound(choice?.name, fresh = choice != null && DashJoin.seenRecently(seen, choice.name, false, nowUs))
    } catch (e: Exception) {
        Log.w(TAG, "Scan lookup failed: ${e.message}")
        com.reau.app.data.RideDiagnostics.warn("wifi", "scan lookup failed: ${e.javaClass.simpleName}: ${e.message}")
        DashFound(null, fresh = false)
    }

    /**
     * Whether the last Wi-Fi scan has [ssid] (or, with [prefix], a network starting with it) seen recently
     * ([DashJoin.SIGHTING_FRESH_MS]: the scan list keeps a dash that was switched off for minutes). False when it does not or
     * when the scan cannot be read or is empty: the caller only acts on a sighting.
     */
    @SuppressLint("MissingPermission")
    fun seenInLastScan(ssid: String, prefix: Boolean): Boolean {
        val seen = readScan()
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        // Every look of every watch passes here: the log says when the list moved, so the dash's age at REau's look is on record (ScanLookLog).
        scanLooks.line(seen, ssid, prefix, nowUs)?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        return DashJoin.seenRecently(seen, ssid, prefix, nowUs)
    }

    /**
     * True while this manager is getting a lost bike network back by itself, recently enough to be trusted
     * (the rule is the top-level [reconnectUnderWay]). [WifiDashLinkUp] then waits for that instead of asking anew.
     */
    fun reconnectUnderWay(nowMs: Long = MonotonicClock.nowMs()): Boolean =
        reconnectUnderWay(wantConnected, everConnected, _state.value.status, nowMs - reconnectActedMs, currentPauseMs(nowMs))

    /** The pause between requests now: the short one, or the long one once the dash has been away for 3 minutes ([ReconnectPace]). */
    private fun currentPauseMs(nowMs: Long): Long = maxOf(absence.pauseMs(nowMs), holdOffMs)

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        // Asked twice by the projection's stop and release; only a request with something to let go is told.
        if (disconnectHasSomethingToLetGo(wantConnected, networkCallback != null, _state.value.status)) {
            com.reau.app.data.RideDiagnostics.log("wifi", "disconnect requested")
        }
        synchronized(requestLock) {
            wantConnected = false
            refusalSerial++
            reconnectJob?.cancel()
            lossWatch?.cancel()
            lostAtUs = 0L
            holdOffMs = 0L
            release()
            synchronized(refusedNetworks) { refusedNetworks.clear() }
            _state.value = WifiState()
        }
        bindRefusals.bound()
        clearStuck()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /** Asks for the bike's network, unless the rider has switched off since this was due (the check and the request are one step). */
    private fun requestNetwork() = synchronized(requestLock) { if (wantConnected) askForTheNetwork() }

    /**
     * Some OEM builds (seen on Android 11) suppress the WifiNetworkSpecifier system dialog
     * entirely when the phone is ALREADY joined to the exact network being requested —
     * requestNetwork() then just times out instead of returning it. Reusing an already-
     * connected match sidesteps that: no new dialog, no request, straight to CONNECTED. False when there is none.
     */
    private fun useAlreadyConnectedNetwork(): Boolean {
        val (activeNetwork, activeSsid) = findAlreadyConnectedDashNetwork() ?: return false
        if (!joinedNetworkUsable(activeNetwork)) {
            com.reau.app.data.RideDiagnostics.warn("wifi", JoinedNetwork.deadLine(activeNetwork, "when it was found"))
            return false
        }
        network = activeNetwork
        // The watcher first: it hears of the network's loss from here on. A network that went before it was registered is told by the check
        // right after, and one that goes in the next moments by [verifyJoinedLater] (31/10/2026: a network of 47 ms of life was taken as
        // connected for good, and no loss was ever heard of).
        watchJoined(activeNetwork)
        if (!joinedNetworkUsable(activeNetwork)) {
            dropWatcher()
            network = null
            com.reau.app.data.RideDiagnostics.warn("wifi", JoinedNetwork.deadLine(activeNetwork, "while it was being taken"))
            return false
        }
        resolvedSsid = activeSsid
        requestAnswered = true
        joinedByPhone = true
        lostAtUs = 0L
        absence.back()
        holdOffMs = 0L
        clearStuck()
        everConnected = true
        joinedOnce = true
        quickRefusals = 0
        // Not remembered as approved: a network the phone joined by itself (a suggestion, a saved one) says nothing
        // about the dialog of a request for it.
        Log.i(TAG, "Using already-connected matching WiFi '${maskSsid(activeSsid)}'")
        val caps = cm.getNetworkCapabilities(activeNetwork)
        com.reau.app.data.RideDiagnostics.log(
            "wifi", "using already-connected WiFi '${maskSsid(activeSsid)}' (network $activeNetwork, ${networkOwner(caps)})",
        )
        noteNetwork(caps)
        onSsidResolved?.invoke(activeSsid)
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
        verifyJoinedLater(activeNetwork)
        return true
    }

    /** Android still knows [joined] as a Wi-Fi network ([JoinedNetwork.usable]); `allNetworks` can list one that is gone. */
    private fun joinedNetworkUsable(joined: Network): Boolean {
        val caps = cm.getNetworkCapabilities(joined)
        return JoinedNetwork.usable(caps != null, caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }

    /** A moment after [joined] was taken, sees that Android still has it; if not, it is a loss like any other ([bikeLost]). */
    private fun verifyJoinedLater(joined: Network) {
        scope.launch {
            delay(JoinedNetwork.CHECK_AFTER_MS)
            if (wantConnected && network == joined && !joinedNetworkUsable(joined)) {
                com.reau.app.data.RideDiagnostics.warn("wifi", JoinedNetwork.deadLine(joined, "${JoinedNetwork.CHECK_AFTER_MS} ms after it was taken"))
                bikeLost()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed request is logged and shown as the ERROR state
    private fun askForTheNetwork() {
        release()
        requestAnswered = false
        requests++
        requestStartMs = MonotonicClock.nowMs()
        reconnectActedMs = requestStartMs
        val timeout = chooseTimeout()
        Log.i(TAG, "Requesting WiFi: '${maskSsid(pendingSsid)}' " +
            "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"}, timeout=${timeout}ms" +
            "${if (requestFirstApproval) ", first approval" else ""})")
        logRequestContext(timeout)
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        if (useAlreadyConnectedNetwork()) return

        val specBuilder = WifiNetworkSpecifier.Builder()
        // The experimental band, decided only for a request that is really made (a network the phone already joined needs none).
        bandChoice.nameOn(specBuilder, band24(), requests)
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
        // Set before the request: its callback can end (onUnavailable) before this thread gets to write it after.
        callbackRegistered = true
        try {
            cm.requestNetwork(request, cb, timeout)
            startAndroid11SsidPolling()
            watchForTheDash(requests)
        } catch (e: Exception) {
            callbackRegistered = false
            Log.e(TAG, "requestNetwork threw: ${e.message}", e)
            com.reau.app.data.RideDiagnostics.error("wifi", "requestNetwork threw: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * While request [request] goes unanswered with the dash not in the scan, looks at the scan every [WATCH_PERIOD_MS] and puts
     * the request out again once the dash shows up (the rule is [DashJoin.refileOnSighting]). The loop ends by itself when
     * another request goes out, the request is answered, or the rider switches off.
     */
    private fun watchForTheDash(request: Int) {
        if (everConnected || requestFirstApproval) return
        // Already in the scan when the request went out: the system should join in a few seconds, and if it has not in
        // [DashJoin.REFILE_SIGHTED_MS] the request is put out again (a request left 30 s was followed by one that joined in 5.4 s).
        val seenAtRequest = seenInLastScan(pendingSsid, pendingPrefix)
        val begun = SystemClock.elapsedRealtime()
        val hold = JoinHold()
        scope.launch {
            while (stillRequesting(request)) {
                delay(WATCH_PERIOD_MS)
                val waited = SystemClock.elapsedRealtime() - begun
                if (!stillRequesting(request)) return@launch
                val seenNow = seenInLastScan(pendingSsid, pendingPrefix)
                val sighted = !seenAtRequest && DashJoin.refileOnSighting(requestFirstApproval, everConnected, false, seenNow, waited)
                val slow = DashJoin.refileSightedAndSlow(requestFirstApproval, everConnected, seenAtRequest, waited, sightedRefiles)
                if (sighted || slow) {
                    if (hold.holds(request)) continue
                    if (slow) sightedRefiles++
                    com.reau.app.data.RideDiagnostics.log("wifi", "the dash ${refileWhy(slow)} $waited ms into request #$request: asking again")
                    requestNetwork()
                    return@launch
                }
            }
        }
    }

    /** The request numbered [request] is the one out, the rider still wants the dash and the system has not answered it. */
    private fun stillRequesting(request: Int) = requests == request && wantConnected && _state.value.status == WifiConnStatus.REQUESTING

    private fun refileWhy(slow: Boolean) = if (slow) "was in the scan when it went out and has not joined" else "showed up in the scan"

    /**
     * The dash's Wi-Fi already joined while a request is still unanswered: that request is holding the network up, and putting
     * it out again would drop it ([DashJoin.holdWhileJoining]). One per watch; remembers since when it holds.
     */
    private inner class JoinHold {
        private val clock = HoldWhileJoining()

        fun holds(request: Int): Boolean {
            val verdict = clock.holds(findAlreadyConnectedDashNetwork() != null, SystemClock.elapsedRealtime())
            if (verdict.began) {
                com.reau.app.data.RideDiagnostics.log("wifi", "the dash's Wi-Fi is already joined in request #$request: leaving it to be answered")
            }
            return verdict.holds
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val scheduledAt = requests
        val pause = currentPauseMs(MonotonicClock.nowMs())
        if (absence.backoffStarted(MonotonicClock.nowMs())) {
            val minutes = ReconnectPace.BACKOFF_AFTER_MS / 60_000
            val cycle = (RECONNECT_TIMEOUT + pause) / 1_000
            com.reau.app.data.RideDiagnostics.log(
                "wifi", "the dash has been away for $minutes min: a request every $cycle s from now; a sighting in the scan brings it back at once",
            )
        }
        reconnectJob = scope.launch {
            delay(pause)
            // The back off of a refused network is one pause (BindRefusals): the next one, if the request ends unanswered, is the usual one.
            holdOffMs = 0L
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
        val sinceFirst = (MonotonicClock.nowMs() - firstRequestMs) / 1_000
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
    private fun lastScanEvidence(): String =
        scanEvidence(readScan(), pendingSsid, pendingPrefix, SystemClock.elapsedRealtime() * MICROS_PER_MILLI, ::emptyListState)

    /** Log only, read when the list came back empty: the state that explains it (see [emptyScanState]). */
    private fun emptyListState(): String {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return emptyScanState(
            locationType = ProjectionService.acceptedTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
            permission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            systemLocationOn = locationManager?.isLocationEnabled,
            importance = me.importance,
            more = EmptyScanFacts(
                lastNonEmpty = ScanHistory.trail.describe(SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime() * MICROS_PER_MILLI),
                wifiJoined = joinedWifi(),
            ),
        )
    }

    /** Log only: whether the phone is joined to a Wi-Fi, and whether the dash's own network is the one ([joinedWifiWords]). */
    @Suppress("DEPRECATION") // allNetworks: the callbacks that replace it do not answer a one-off question
    private fun joinedWifi(): String? = runCatching {
        val wifiNetworks = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        joinedWifiWords(wifiNetworks.size, network?.let { it in wifiNetworks } == true)
    }.getOrNull()

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
            com.reau.app.data.RideDiagnostics.log(
                "wifi", releaseLine(requests, MonotonicClock.nowMs() - requestStartMs, status, requestAnswered),
            )
            // A request let go while the system had not answered it: the next one starts the system's dialog over.
            if (!requestAnswered) releasedUnansweredMs = SystemClock.elapsedRealtime()
        }
        if (callbackRegistered) networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callbackRegistered = false
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
        val request = requests
        scope.launch {
            delay(5_000)
            repeat(6) {
                // Not for a request that was let go, nor after the rider switched off: it would put CONNECTED back on a disconnected manager.
                if (!wantConnected || requests != request) return@launch
                if (_state.value.status == WifiConnStatus.CONNECTED && resolvedSsid != null) return@launch
                findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
                    network = activeNetwork
                    resolvedSsid = activeSsid
                    requestAnswered = true
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
    @Suppress("DEPRECATION") // allNetworks: the callbacks that replace it do not answer a one-off question
    private fun findAlreadyConnectedDashNetwork(): Pair<Network, String>? {
        val activeSsid = readActiveWifiSsid()?.takeIf { matchesPendingSsid(it, pendingSsid, pendingPrefix) } ?: return null
        val wifiNetwork = cm.allNetworks.firstOrNull { candidate ->
            if (isRefused(candidate)) return@firstOrNull false
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
    @Suppress("DEPRECATION") // ScanResult.SSID, as in searchDashSsid
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
            // A late callback of a request that was let go (the rider switched off) must not bring the manager back.
            if (!wantConnected) return
            this@DashWifiManager.network = network
            requestAnswered = true
            joinedByPhone = false
            lostAtUs = 0L
            absence.back()
            holdOffMs = 0L
            clearStuck()
            unavailableInARow = 0
            val tookMs = MonotonicClock.nowMs() - requestStartMs
            everConnected = true
            joinedOnce = true
            quickRefusals = 0
            reconnectJob?.cancel()
            noteNetwork(cm.getNetworkCapabilities(network))
            // SSID is read in onCapabilitiesChanged (transportInfo isn't populated here
            // yet on Android 13+). Try once anyway, else fall back to the pending value.
            val resolved = resolveSsid(network).takeIf { it.isNotBlank() } ?: pendingSsid
            rememberJoined(resolved)
            Log.i(TAG, "WiFi connected ✓  ssid='${maskSsid(resolved)}' network=$network (${tookMs}ms)")
            com.reau.app.data.RideDiagnostics.log(
                "wifi", "connected '${maskSsid(resolved)}' in ${tookMs}ms (network $network, ${networkOwner(cm.getNetworkCapabilities(network))})",
            )
            com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(true))
            if (resolved.isNotBlank() && resolved != pendingSsid) onSsidResolved?.invoke(resolved)
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
            onNetworkAvailable?.invoke()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!wantConnected) return
            noteNetwork(caps)
            resolveSsidFrom(network, caps)
        }

        override fun onUnavailable() {
            // Android has dropped the callback itself, wanted or not.
            callbackRegistered = false
            if (!wantConnected) return
            bandChoice.endedUnanswered()
            this@DashWifiManager.network = null
            val tookMs = MonotonicClock.nowMs() - requestStartMs
            // Mid-ride drop where the dash AP went missing (we HAD connected): keep retrying so a
            // dash that's hung — and revived by a bike power-cycle — auto-rejoins with no manual
            // step. The ViewModel's reconnect-giveup timer still bounds this if it never returns.
            if (shouldRetryOnUnavailable(everConnected, wantConnected)) {
                Log.w(TAG, "WiFi unavailable mid-session (${tookMs}ms) — dash AP missing, retrying in ${RECONNECT_DELAY}ms")
                // The first of an absence, then every fifth with the count: a long absence wrote a trio of lines at each try.
                if (unavailableLogDue(++unavailableInARow)) {
                    com.reau.app.data.RideDiagnostics.warn(
                        "wifi", "unavailable after ${tookMs}ms (mid-session, #$unavailableInARow of this absence) — retrying in " +
                            "${RECONNECT_DELAY}ms; ${lastScanEvidence()}, approvedBefore=${approvedBefore()}",
                    )
                }
                _state.value = WifiState(
                    status = WifiConnStatus.REQUESTING,
                    ssid   = pendingSsid,
                    error  = context.getString(com.reau.app.R.string.dash_err_wifi_lost),
                )
                reconnectActedMs = MonotonicClock.nowMs()
                noteFailedTry()
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

        override fun onLosing(network: Network, maxMsToLive: Int) {
            if (wantConnected) com.reau.app.data.RideDiagnostics.warn("wifi", LinkLoss.losingLine(maxMsToLive))
        }

        override fun onLost(network: Network) = bikeLost()
    }

    /**
     * Canonical place to read the connected SSID — REQUIRED for auth, since the dash validates the SSID inside the encrypted
     * handshake (DashAuth). Without the real SSID (prefix-discovery), every auth is rejected.
     */
    private fun resolveSsidFrom(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo ?: return
        val ssid = normalizeSsid(info.ssid)
        if (ssid.isBlank() || ssid == resolvedSsid) return
        resolvedSsid = ssid
        rememberJoined(ssid)
        this.network = network
        Log.i(TAG, "Resolved dash SSID: '${maskSsid(ssid)}'")
        com.reau.app.data.RideDiagnostics.log("wifi", "resolved SSID via onCapabilitiesChanged: '${maskSsid(ssid)}'")
        onSsidResolved?.invoke(ssid)
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
    }

    /**
     * Whether the network came from a request with a specifier. A non-null specifier says yes; a null one is not "no" (Android may
     * leave it out of a callback that has no privilege to see it), so it says no only for a network the phone joined by itself
     * ([joinedByPhone]) and is unknown otherwise, and below API 30, which does not say.
     */
    private fun hasSpecifier(caps: NetworkCapabilities): Boolean? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> null
        caps.networkSpecifier != null -> true
        joinedByPhone -> false
        else -> null
    }

    /**
     * Keeps what Android says of the network that is up ([NetFacts]); the first time the channel is known after a loss, says whether
     * the dash came back on it. [caps] null (Android has no snapshot yet) changes nothing.
     */
    private fun noteNetwork(caps: NetworkCapabilities?) {
        val before = netFacts
        val info = caps?.transportInfo as? WifiInfo
        val mhz = info?.frequency?.takeIf { it > 0 } ?: before?.mhz
        val facts = NetFacts(
            upAtMs = before?.upAtMs ?: MonotonicClock.nowMs(),
            mhz = mhz,
            rssiDbm = info?.rssi?.takeIf { it > NO_RSSI } ?: before?.rssiDbm,
            specifier = caps?.let(::hasSpecifier) ?: before?.specifier,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: before?.validated,
            notRestricted = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ?: before?.notRestricted,
        )
        netFacts = facts
        if (awaitingChannel && mhz != null) {
            awaitingChannel = false
            LinkLoss.backLine(lostMhz, mhz)?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        }
    }

    /** The log line for a passphrase Android refused: whatever Android says, the password itself is not in it. */
    private fun passphraseRefusedLine(e: IllegalArgumentException): String =
        "the dash password was refused by Android: ${e.javaClass.simpleName}: ${e.message?.replace(pendingPassword, "…")}"

    /** The bike network went: forgotten, and asked for again while the rider still wants the dash. */
    private fun bikeLost() {
        if (!wantConnected) return
        Log.w(TAG, "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms")
        val now = MonotonicClock.nowMs()
        absence.lost(now)
        ImageGap.clock.lost(now, DashHeard.agoMs(now))
        stuck.lost(now, dashSpoke = DashHeard.agoMs(now) != null)
        val screenOn = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
        com.reau.app.data.RideDiagnostics.warn(
            "wifi", LinkLoss.lostLine(netFacts, now, DashHeard.agoMs(now), screenOn, RECONNECT_DELAY),
        )
        // What the phone itself says of its Wi-Fi and networks at this moment (the system's own lines are collected by adb, not by the app).
        com.reau.app.data.RideDiagnostics.log("wifi", PhoneSide.now(context))
        DashHeard.trail?.report("the Wi-Fi was lost", now)?.forEach { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        // What was going out in the seconds before the silence: RTP per second, the biggest burst, the last keyframe and command.
        DashHeard.tail?.reportOnce("the Wi-Fi was lost", netFacts?.let { "on this network for ${(now - it.upAtMs).coerceAtLeast(0) / MS_PER_SECOND} s" })
            ?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        // The next connection of this projection is compared with the channel this one had (kept if it is not known now).
        netFacts?.mhz?.let { lostMhz = it }
        awaitingChannel = lostMhz != null
        netFacts = null
        com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(false))
        network = null
        _state.value = WifiState(
            status = WifiConnStatus.REQUESTING,
            ssid   = pendingSsid,
            error  = context.getString(com.reau.app.R.string.dash_err_link_lost),
        )
        reconnectActedMs = MonotonicClock.nowMs()
        if (wantConnected) {
            scheduleReconnect()
            watchAfterLoss()
        }
    }

    /**
     * While the dash's network stays lost, looks at the scan every [WATCH_PERIOD_MS] and asks for the network at once when the
     * dash shows up newer than the loss, cutting the 4 s pause or a request already out ([DashJoin.refileAfterLoss]). One watch
     * for a loss; it ends when the network is back or the rider switches off.
     */
    private fun watchAfterLoss() {
        lostAtUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        lastRefileMs = 0L
        if (lossWatch?.isActive == true) return
        lossWatch = scope.launch {
            // The join of the dash's Wi-Fi is held for its own few seconds in each loss (the watch lasts as long as the loss does).
            val hold = HoldWhileJoining()
            while (stillLost()) {
                delay(WATCH_PERIOD_MS)
                if (stillLost() && refileForSighting(hold)) requestNetwork()
            }
        }
    }

    /** The rider still wants the dash and the network lost has not come back. */
    private fun stillLost() = wantConnected && lostAtUs != 0L && network == null

    /**
     * The decision of [watchAfterLoss] for this moment, with the line that tells it. A request is not put out again while the dash's Wi-Fi
     * is already joined and waiting to be answered ([DashJoin.refileAfterLossHeld]): letting the request go takes that network down.
     */
    private fun refileForSighting(hold: HoldWhileJoining): Boolean {
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        val nowMs = MonotonicClock.nowMs()
        val pause = reconnectJob?.isActive == true
        val sinceLostMs = (nowUs - lostAtUs) / MICROS_PER_MILLI
        val decision = DashJoin.refileAfterLossHeld(
            requestFirstApproval, lostAtUs, newestSightingUs(), nowUs,
            requestAgeMs = nowMs - requestStartMs, inPause = pause,
            sinceLastRefileMs = if (lastRefileMs == 0L) null else nowMs - lastRefileMs,
            hold = hold, nowMs = SystemClock.elapsedRealtime(), joiningNow = { findAlreadyConnectedDashNetwork() != null },
        )
        when (decision) {
            DashJoin.LossRefile.NO, DashJoin.LossRefile.HELD -> Unit
            DashJoin.LossRefile.HELD_FIRST -> com.reau.app.data.RideDiagnostics.log(
                "wifi", "the dash showed up in the scan $sinceLostMs ms after the Wi-Fi was lost and its Wi-Fi is already joined in request #$requests: " +
                    "leaving it to be answered",
            )
            DashJoin.LossRefile.REFILE -> {
                lastRefileMs = nowMs
                reconnectJob?.cancel()
                com.reau.app.data.RideDiagnostics.log(
                    "wifi", "the dash showed up in the scan $sinceLostMs ms after the Wi-Fi was lost: " +
                        "asking again" + if (pause) " (the pause is cut short)" else "",
                )
                // A request let go before its time, with the dash in the scan, is a try that did not get it (a request that ended already counted).
                if (!pause) noteFailedTry()
            }
        }
        return decision == DashJoin.LossRefile.REFILE
    }

    // ── A hung dash, and a network Android refuses ─────────────────────────

    /** The last scan as [ScanSeen]s; null when it cannot be read. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // ScanResult.SSID, as in searchDashSsid
    private fun readScan(): List<ScanSeen>? = runCatching {
        wifi.scanResults.mapNotNull { r ->
            r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { ScanSeen(it, r.level, r.frequency, r.timestamp) }
        }
    }.getOrNull()?.also { ScanHistory.trail.note(it.size, SystemClock.elapsedRealtime(), newestStamp(it)) }

    /**
     * A try for the dash's network ended without it (the request ran out, or was let go before its time). With the dash in the scan at a good
     * signal, enough of them in a row mean its Wi-Fi hung ([StuckDashWatch]): the ride log says so once, and the rider is told to switch the
     * ignition off and on.
     */
    private fun noteFailedTry() {
        val seen = readScan()
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        val sighting = DashStuck.sighting(seen, pendingSsid, pendingPrefix, nowUs)
        val verdict = stuck.failedTry(MonotonicClock.nowMs(), sighting) ?: return
        val rssi = seen.orEmpty().filter { matchesPendingSsid(it.ssid, pendingSsid, pendingPrefix) }.maxOfOrNull { it.level }
        com.reau.app.data.RideDiagnostics.warn("wifi", DashStuck.line(verdict.absentMs, verdict.tries, rssi))
        onDashStuck?.invoke(true)
    }

    /** The absence is over (the dash is back, or the rider switched off): the rider's notice goes if it was shown. */
    private fun clearStuck() {
        if (stuck.back()) {
            com.reau.app.data.RideDiagnostics.log("wifi", "the dash is back (or REau was switched off): the notice of the hung dash is taken away")
            onDashStuck?.invoke(false)
        }
    }

    private fun isRefused(candidate: Network): Boolean = synchronized(refusedNetworks) { candidate in refusedNetworks }

    private fun rememberRefused(refused: Network) = synchronized(refusedNetworks) {
        if (refused !in refusedNetworks) refusedNetworks.add(refused)
        while (refusedNetworks.size > MAX_REFUSED) refusedNetworks.removeAt(0)
    }

    /**
     * How binding the session's sockets to [bound] went ([DashSession.onBindResult]). A refusal ([refused], EPERM) means that network is no
     * good: it is remembered so it is not taken as "already connected" again, its request and watcher are let go, and the network is asked for
     * again at once the first time; if the next one is refused too, after [BindRefusals.BACKOFF_BASE_MS] and more at each one in a row (the
     * session's own retry in between finds the manager asking, [reconnectUnderWay]). Ride log of 31/10/2026: the same dead network was handed
     * to the session every 30 s for 4.5 minutes. A bind that worked starts the count over.
     */
    fun bindResult(bound: Network?, refused: Boolean) {
        if (!refused) {
            bindRefusals.bound()
            return
        }
        synchronized(requestLock) {
            if (!wantConnected) return
            val current = network
            if (bound != null && current != null && bound != current) {
                com.reau.app.data.RideDiagnostics.log("wifi", "a socket was refused on network $bound, but the network in use is $current: left as it is")
                return
            }
            val dead = bound ?: current
            // Whose it was, said before the network is let go (the phone's own join is not REau's request going wrong).
            val phoneJoined = current != null && joinedByPhone
            val owner = networkOwner()
            val verdict = bindRefusals.refused(dead, phoneJoined)
            com.reau.app.data.RideDiagnostics.warn("wifi", verdict.line(dead, owner))
            val serial = ++refusalSerial
            dead?.let(::rememberRefused)
            release()
            holdOffMs = if (verdict.step == BindRefusals.Step.BACK_OFF) verdict.backoffMs else 0L
            _state.value = WifiState(
                status = WifiConnStatus.REQUESTING,
                ssid   = pendingSsid,
                error  = context.getString(com.reau.app.R.string.dash_err_link_lost),
            )
            reconnectActedMs = MonotonicClock.nowMs()
            if (verdict.step == BindRefusals.Step.ASK_AGAIN) {
                askForTheNetwork()
                tellTheProjectionSoon(serial)
            } else {
                scheduleReconnect()
            }
        }
    }

    /** Whose the network in use is, for the ride log ([NetworkOwner]). */
    private fun networkOwner(caps: NetworkCapabilities? = null): String = NetworkOwner.words(
        joinedByPhone, requests,
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ?: netFacts?.notRestricted,
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: netFacts?.validated,
    )

    /**
     * The failed session has told the projection to wait for its own retry (30 s after many tries) by the time the network asked for again after
     * a refusal is up. [RefusalRetry.AFTER_MS] later, with that network up, the projection is told to try the dash now (ride log of 06/11/2026,
     * 17:47:21: another network was up 20 ms after the refusal and the dash was tried 30 s later). With the request still unanswered, its answer does it.
     */
    private fun tellTheProjectionSoon(serial: Int) {
        scope.launch {
            delay(RefusalRetry.AFTER_MS)
            val up = synchronized(requestLock) {
                val now = network
                val due = RefusalRetry.due(
                    wantConnected, serial != refusalSerial, _state.value.status == WifiConnStatus.CONNECTED && now != null,
                    now != null && isRefused(now),
                )
                now.takeIf { due }
            } ?: return@launch
            com.reau.app.data.RideDiagnostics.log("wifi", RefusalRetry.line(up, RefusalRetry.AFTER_MS))
            onNetworkAvailable?.invoke()
        }
    }

    /** Lets go of the watcher of a network the phone joined by itself (the request, if there is one, stays). */
    private fun dropWatcher() {
        if (callbackRegistered) networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callbackRegistered = false
        networkCallback = null
    }

    /** The newest stamp of the dash in the last scan; null for none, an empty scan or one that cannot be read. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // ScanResult.SSID, as in searchDashSsid
    private fun newestSightingUs(): Long? = runCatching {
        wifi.scanResults.filter { r ->
            r.SSID?.trim('"')?.let { it.isNotBlank() && matchesPendingSsid(it, pendingSsid, pendingPrefix) } == true
        }.maxOfOrNull { it.timestamp }
    }.getOrNull()

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
            callbackRegistered = true
        } catch (e: RuntimeException) {
            com.reau.app.data.RideDiagnostics.warn("wifi", "could not watch the joined WiFi: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
