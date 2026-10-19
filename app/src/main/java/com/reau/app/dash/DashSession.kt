// [stats] counts what the session and the dash exchange (at the socket, and the heartbeats here), one dash-stats line
// every statsEveryMs. The dash's name goes to the log masked.
package com.reau.app.dash

import android.util.Log
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.dash.nav.DashManeuverGlyphs
import com.reau.app.util.Dbg
import com.reau.app.dash.protocol.K1GPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * Dash session, sequenced to match better-dash's app-like navigation sequence:
 *   1. Open sockets (RX :2002 bound first).
 *   2. Send initial burst on :2000 (includes the request for the dash's public key).
 *   3. RX loop ingests 07 00 / 07 03 → sends the session key → waits for 07 01 01.
 *   4. Nav entry: route-card ×4 → projectionFrame → navStart (once) → route-card.
 *   5. Start RTP + 4 Hz projection heartbeat + 1 Hz route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, 09 06 IDR-decoded acks,
 * and 09 00 button events.
 */
// the session owns the whole link to the dash; splitting it is a refactor of its own; the parameters are the fields of what it builds (wire format / the data it carries)
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
class DashSession(
    private val scope: CoroutineScope,
    // The ONLY way a test substitutes a fake dash for the
    // real one — every real caller never passes this, so `DashSocket(it)`
    // (today's exact call, byte for byte) is what actually runs. A test passes a factory that
    // builds a DashSocket with an injected ctrlDest/rtpDest pointing at the fake dash's own
    // sockets instead.
    private val socketFactory: (android.net.Network?) -> DashSocket = { DashSocket(it) },
    // sendRealPhoneStatus: additive, same shape as
    // socketFactory above — every real caller today never passes this, so
    // the default `{ null }` (today's exact heartbeat bytes, see DashCommands.heartbeat's own
    // `status=null` golden test) is what actually runs. DashSession has no Context/Android
    // dependency of its own (deliberately — see this class's own header doc), so it can't read the
    // phone's real state itself; the caller supplies it here instead, gated on its own
    // dashConfig.sendRealPhoneStatus flag. Called once per 1 Hz heartbeat tick, not cached — a
    // status changing mid-ride (phone starts charging, GPS toggled) is picked up on its own next tick.
    private val phoneStatusProvider: () -> com.reau.app.dash.protocol.DashCommands.PhoneStatus? = { null },
    /** The weather for the heartbeat (behind "Clima no painel"); null says "no data" to the dash. */
    private val weatherProvider: () -> com.reau.app.dash.protocol.DashCommands.Weather? = { null },
    /** How often the dash-stats line is written. */
    private val statsEveryMs: Long = STATS_EVERY_MS,
    /**
     * The phone's Wi-Fi link to the dash at this moment: what the ride log writes next to each watchdog, to
     * tell a weak or slow Wi-Fi from a dash that went quiet. Like [socketFactory], this class has no Context of its own:
     * the service passes [PhoneLinkProbe.read]; the default reads nothing.
     */
    private val linkProbe: (android.net.Network?) -> LinkSample? = { null },
    /** Everything the dash says, for the screen "Dados da moto"; null keeps it to itself, as a test does. */
    private val readings: DashReadingSink? = null,
) {

    /** What this session and the dash exchanged. */
    val stats = DashStats(onFirst = { com.reau.app.data.RideDiagnostics.log("dash", it) })
    private var statsJob: Job? = null
    @Volatile private var statsWindowStartMs = 0L

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    // Every 0x0F (decrypted vehicle-secure)/0x0C (dash telemetry)/unrecognized TLV, for the
    // Telemetry Lab (debug-only) — see TelemetryEvent's own doc. Buffered + drop-oldest so a
    // burst never blocks the RX loop reading real protocol traffic; consumers that care about
    // every event should keep up promptly, they're not the source of truth (RideDiagnostics is).
    private val _telemetry = MutableSharedFlow<com.reau.app.dash.protocol.TelemetryEvent>(
        extraBufferCapacity = 200, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val telemetry = _telemetry.asSharedFlow()

    private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0
    // Wall-clock of the last packet received from the dash — fed to the RX watchdog.
    @Volatile private var lastRxMs = 0L
    // One-shot: the first time the dash reports it DECODED our video (09 06 55). If this never
    // appears in the ride log, the dash never accepted the stream — the likely "Timeout!" cause.
    @Volatile private var loggedFirstAck = false
    // Running count of 09 06 55 + 09 04 55 decode acks — the only dash-side signal of frames it
    // actually decoded. Read as deltas per window by the automatic-fps controller.
    private val decodeAcks = java.util.concurrent.atomic.AtomicLong(0)
    val decodedFrameCount: Long get() = decodeAcks.get()

    /** True once the dash has confirmed decoding a frame of THIS stream (09 06 55). The frame loop keeps the
     *  still-map pace until then — see DashLogic.fullRate. */
    val firstFrameConfirmed: Boolean get() = loggedFirstAck || aliveWithoutAck

    // The dash went on answering (heartbeats, buttons) for FIRST_ACK_TIMEOUT_MS without one 09 06 55: taken as showing
    // the video. A ride: after a drop in the middle of a call the dash showed the map and sent no 09 06 55 any more,
    // and the first-ack watchdog ended a working session every 15 s for minutes.
    @Volatile private var aliveWithoutAck = false

    /** Wall-clock of the last time a watchdog below found the dash silent or never decoding; 0 = never. Kept across
     *  reconnections on purpose: the next stream reads it to tell a dash that choked from one that was switched off
     *  (DashLogic.dashFailedWhileReachable). */
    @Volatile var watchdogFailMs = 0L
        private set
    // Wall-clock of entering STREAMING — see firstAckTimedOut's own doc for the real gap this closes.
    @Volatile private var streamingStartMs = 0L
    // The network of this session, and the minute of Wi-Fi link before now (one sample every 5 s): see [linkProbe].
    @Volatile private var linkNetwork: android.net.Network? = null
    private val linkHistory = LinkHistory()
    @Volatile private var linkBaselineLogged = false
    // Set at the very start of disconnect(), before sock.close() — closing the socket unblocks
    // rxJob's blocking sock.receive() with an OS-level exception (EBADF), which would otherwise
    // reach fail() and flip _state to ERROR, racing disconnect()'s own unconditional _state = IDLE
    // write on a different coroutine (rxJob?.cancel() is cooperative and does nothing to a call
    // already blocked inside a synchronous receive). fail() itself checks this flag — see its own
    // kdoc for the full story, including the live failure this closes.
    @Volatile private var disconnecting = false

    // Which connect() the running session belongs to. Every coroutine a session launches
    // carries the number it was launched with, and fail()/READY from a session that is not the current one are
    // ignored: before this, a session that failed during authentication (its socket closed by fail()) kept its
    // runSession coroutine waiting for 07 01 01 for up to AUTH_TIMEOUT, and when a new connect() had meanwhile
    // started the next session, that leftover's own fail() closed the new session's socket and put it in ERROR, or
    // its READY went out on a socket already closed.
    @Volatile private var sessionGeneration = 0

    var onButton: ((com.reau.app.dash.protocol.DashButtonEvent) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    private var notices0904 = 0
    private var notices0906 = 0

    @Volatile var destinationName: String = DEFAULT_DESTINATION

    private var sessionJob: Job? = null
    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navInfoJob: Job? = null
    private var cardsJob: Job? = null

    // Live nav-info pushed to the dash bubble at ~1 Hz (set by NavEngine output).
    @Volatile private var navManeuver = DashCommands.NAV_MANEUVER_CONTINUE
    @Volatile private var navPrimaryDist = 0
    @Volatile private var navPrimaryUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navTotalDist = 0
    @Volatile private var navTotalUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navEta: String? = null
    // The street of the next maneuver, for the dash only; blank when Android Auto does not say it.
    @Volatile private var navRoad = ""
    @Volatile private var navActive = false
    /** What the route card says with no route (the joystick in the dash's map mode depends on it). */
    @Volatile var idleCard = DashIdleCard.PIN

    /** Rider's choice (Settings): false keeps the dash's own maneuver circle hidden even while guiding. */
    @Volatile var maneuverCircleEnabled = true

    // The last maneuver of this guidance the dash could draw, but the rerouting
    // compass; and whether the ring is keeping it now (said once per stretch in the ride log).
    @Volatile private var lastVisibleManeuver: Int? = null
    @Volatile private var keepingManeuver = false

    // Behind the switch: the real time left and the maneuver after the next.
    @Volatile private var navRemaining = DashCommands.REMAINING_OMIT
    @Volatile private var navNext = DashCommands.NEXT_MANEUVER_NONE
    @Volatile private var navExtras = false

    /**
     * Push the latest turn-by-turn figures; sent to the dash at 1 Hz. A [maneuver] the dash cannot draw (Waze's
     * `UNKNOWN` on a stretch with no turn ahead, which arrives as the hidden bubble) never reaches the ring: the ring
     * keeps the last maneuver it could draw, or straight on ([ringManeuver]), because with the ring hidden the dash
     * stops passing the map-mode joystick on.
     */
    @Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
    fun updateNavInfo(
        maneuver: Int, primaryDist: Int, primaryUnit: Int,
        totalDist: Int, totalUnit: Int, etaHHMM: String? = null, road: String = "",
        remainingSeconds: Long = DashCommands.REMAINING_OMIT, nextManeuver: Int = DashCommands.NEXT_MANEUVER_NONE,
        extras: Boolean = false,
    ) {
        val shown = ringManeuver(maneuver, lastVisibleManeuver)
        if (shown != maneuver) {
            if (!keepingManeuver) {
                com.reau.app.data.RideDiagnostics.log("dash", "nav: Android Auto sent no drawable maneuver, kept 0x%02X".format(shown))
            }
            keepingManeuver = true
        } else {
            keepingManeuver = false
            if (maneuver != DashManeuverGlyphs.RECALCULATING) lastVisibleManeuver = maneuver
        }
        navManeuver = shown
        navPrimaryDist = primaryDist
        navPrimaryUnit = primaryUnit
        navTotalDist = totalDist
        navTotalUnit = totalUnit
        navEta = etaHHMM
        navRoad = road
        navRemaining = remainingSeconds
        navNext = nextManeuver
        navExtras = extras
        navActive = true
    }

    /** For the tests: what the ring carries now. */
    internal val ringNow: Int get() = navManeuver

    /** No guidance any more: the nav packets stop and the route card goes back to the card with no route. */
    fun clearNavInfo() {
        navActive = false
        navRoad = ""
        forgetLastManeuver()
    }

    /** A guidance's last maneuver never carries over to the next. */
    private fun forgetLastManeuver() {
        lastVisibleManeuver = null
        keepingManeuver = false
    }

    // The dash's own music and call cards, at 1 Hz while streaming.
    @Volatile private var nowPlayingPackets: List<ByteArray>? = null
    private var musicShown = false
    // The album's picture: its packets, the picture last sent, and whether the dash still has to get them.
    @Volatile private var coverSlices: List<ByteArray>? = null
    @Volatile private var coverSent: ByteArray? = null
    @Volatile private var coverPending = false
    @Volatile private var callerName: String? = null
    // The call goes on after it is answered: the card says "in call" (05 21 = 0x14) with the same name until the call
    // ends.
    @Volatile private var callerInCall = false
    private var callCardShown = false
    private var lastCardState: DashCommands.CallState? = null

    /**
     * The song on the dash's own music card (title, album, artist), repeated every second, with the card's flags around
     * the text, in this order: music on, the text, no album art, playing or paused.
     */
    fun updateNowPlaying(title: String, album: String, artist: String, playing: Boolean = true, coverJpeg: ByteArray? = null) {
        val chunks = coverJpeg?.let(DashCommands::albumArtChunks)?.takeIf { it.isNotEmpty() }
        // A new picture goes out once, when the track changes; the same one is not sent again.
        if (chunks == null) {
            coverSlices = null
        } else {
            if (!coverJpeg.contentEquals(coverSent)) {
                coverSent = coverJpeg
                coverPending = true
            }
            coverSlices = chunks
        }
        nowPlayingPackets = listOf(
            DashCommands.musicActive(true),
            DashCommands.nowPlaying(title, album, artist),
            DashCommands.musicCover(chunks != null),
            DashCommands.musicPlaying(playing),
        )
    }

    /** Nothing playing; the last card is no longer repeated, and the dash is told once there is no song. */
    fun clearNowPlaying() {
        nowPlayingPackets = null
        coverSlices = null
        coverSent = null
    }

    /** The call as the status packets say it every second: ringing, in a call, or none. */
    private fun callNow(): DashCommands.CallState = when {
        callerName == null -> DashCommands.CallState.NONE
        callerInCall -> DashCommands.CallState.IN_CALL
        else -> DashCommands.CallState.RINGING
    }

    /**
     * A call ringing, or answered and going on ([inCall]); its card goes out at once and every
     * second until [clearCaller].
     */
    fun showCaller(name: String, inCall: Boolean = false) {
        callerName = name
        callerInCall = inCall
        sendCardsNow()
    }

    /** The call is over; the card is cleared at once. */
    fun clearCaller() {
        if (callerName == null) return
        callerName = null
        callerInCall = false
        sendCardsNow()
    }

    private fun sendCardsNow() {
        if (_state.value == DashState.STREAMING) scope.launch(Dispatchers.IO) { sendCards() }
    }

    /** One round of the cards: the call card (or its clear, once) and the music card. */
    @Synchronized
    private fun sendCards() {
        val sock = socket ?: return
        val caller = callerName
        if (caller != null) {
            // The call card: the state then the name, encrypted under the session key.
            val state = callNow()
            runCatching { sock.send(DashCommands.callState(state)) }
            runCatching { sock.send(DashCommands.callStateAfter()) }
            auth?.sessionKey?.let { key -> runCatching { sock.send(DashCommands.callNotify(caller, key)) } }
            if (!callCardShown || state != lastCardState) {
                com.reau.app.data.RideDiagnostics.log("dash", "call card shown (${state.name.lowercase()})")
            }
            lastCardState = state
            callCardShown = true
        } else if (callCardShown) {
            runCatching { sock.send(DashCommands.callState(DashCommands.CallState.NONE)) }
            runCatching { sock.send(DashCommands.callStateAfter()) }
            com.reau.app.data.RideDiagnostics.log("dash", "call card cleared")
            callCardShown = false
        }
        val music = nowPlayingPackets
        if (music != null) {
            music.forEach { runCatching { sock.send(it) } }
            if (!musicShown) com.reau.app.data.RideDiagnostics.log("dash", "music card shown")
            musicShown = true
            sendCoverIfDue(sock)
        } else if (musicShown) {
            runCatching { sock.send(DashCommands.musicActive(false)) }
            com.reau.app.data.RideDiagnostics.log("dash", "music card cleared")
            musicShown = false
        }
    }

    /** The album's picture, once, in its slices with a pause between them; the card's flag said it comes. */
    private fun sendCoverIfDue(sock: DashSocket) {
        val slices = coverSlices ?: return
        if (!coverPending) return
        coverPending = false
        for ((i, slice) in slices.withIndex()) {
            if (i > 0) Thread.sleep(DashCommands.COVER_PAUSE_MS)
            runCatching { sock.send(slice) }
        }
        com.reau.app.data.RideDiagnostics.log("dash", "album picture sent in ${slices.size} slices")
    }

    /**
     * Route card with the LIVE nav figures patched in. The template's captured
     * values (7.9 km / glyph 0x3C / ETA 03:03) must never reach the dash once
     * real guidance is running — the card repeats at 1 Hz and would stomp the
     * activeNavPacket numbers every second.
     */
    private fun liveRouteCard(projectionOn: Boolean): ByteArray =
        if (navActive) DashCommands.routeCard(
            // The street while guiding, else what the dash is showing ("Waze").
            navRoad.ifBlank { destinationName }, projectionOn,
            maneuver = ringGlyph(),
            primaryUnit = navPrimaryUnit,
            totalDist = navTotalDist,
            totalUnit = navTotalUnit,
            etaHHMM = navEta,
            decimalComma = decimalComma(),
            remainingSeconds = navRemaining.takeIf { navExtras },
            nextManeuver = nextShown(),
            proximity = proximityBar(),
        )
        else DashCommands.idleRouteCard(idleCard, destinationName, projectionOn, clockHHMM())

    /** The phone's own decimal separator is a comma (pt-BR): the dash writes its distances so (05 0A). */
    private fun decimalComma(): Boolean = java.text.DecimalFormatSymbols.getInstance().decimalSeparator == ','

    /** The route card as it goes to the dash now (internal, for the tests). */
    internal fun routeCardNow(): ByteArray = liveRouteCard(projectionOn = true)

    /**
     * The maneuver after the next (05 03) with the switch on: shown only from 100 m of the turn (the proximity bar below
     * 5), 0xFF otherwise. Null (the template's, or none) with the switch off.
     */
    private fun nextShown(): Int? = when {
        !navExtras -> null
        proximityBar() >= FAR_BAR -> DashCommands.NEXT_MANEUVER_NONE
        else -> navNext
    }

    /** The proximity bar (05 0C) for the distance to the turn, in metres whatever the unit the dash shows. */
    private fun proximityBar(): Int {
        val metres = if (navPrimaryUnit == DashCommands.NAV_UNIT_KM_TENTHS) navPrimaryDist * 100 else navPrimaryDist
        val keep = navManeuver == com.reau.app.dash.nav.DashManeuverGlyphs.FORK_LEFT || navManeuver == com.reau.app.dash.nav.DashManeuverGlyphs.FORK_RIGHT
        return DashCommands.proximityLevel(metres, keep)
    }

    /** The ring's glyph while guiding: the maneuver itself (hiding it never worked). */
    private fun ringGlyph(): Int = bubbleManeuver(maneuverCircleEnabled, navManeuver)

    /** The phone's clock as 4 digits, the ETA of the idle card's arrival pin. */
    private fun clockHHMM(): String = java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String, network: android.net.Network? = null) {
        // Flip to CONNECTING synchronously, before launching — otherwise two near-simultaneous
        // callers (the WiFi-reconnect and session-retry paths of the caller both call connect()
        // after an error) can both pass this guard while _state is still IDLE/ERROR, each starting
        // its own runSession() and binding the same fixed UDP ports; the second one's fail() then
        // closes the `socket` field out from under the first, working session.
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        disconnecting = false
        // Whatever an earlier session still runs (its session coroutine waiting for the dash's answer, its
        // stats line, its cards) is ended before this one starts, and this one gets a number of its own.
        sessionJob?.cancel(); statsJob?.cancel(); cardsJob?.cancel()
        val mine = ++sessionGeneration
        _state.value = DashState.CONNECTING
        Log.i(TAG, connectLine(ssid, network))
        com.reau.app.data.RideDiagnostics.log("session", connectLine(ssid, network))
        sessionJob = scope.launch(Dispatchers.IO) { runSession(mine, ssid, network) }
    }

    /** Whether a session numbered [generation] is the one connect() started last, and not being torn down. */
    private fun isCurrent(generation: Int): Boolean = generation == sessionGeneration && !disconnecting

    /** Test-only: which of the session's coroutines are still active, by name. */
    internal fun testOnlyActiveJobs(): Set<String> = buildSet {
        if (sessionJob?.isActive == true) add("session")
        if (statsJob?.isActive == true) add("stats")
        if (cardsJob?.isActive == true) add("cards")
        if (rxJob?.isActive == true) add("rx")
        if (heartbeatJob?.isActive == true) add("heartbeat")
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        _state.value = DashState.STREAMING
        streamingStartMs = System.currentTimeMillis()
        coverPending = coverSlices != null // a stream is new to the dash: the picture again
        com.reau.app.data.RideDiagnostics.log("session", "→ STREAMING")
        // Projection-on route card + faster keep-alive begin now
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchCards()
    }

    fun sendRtp(packet: ByteArray) { socket?.sendRtp(packet) }

    fun updateRouteCard(name: String) {
        destinationName = name.ifBlank { DEFAULT_DESTINATION }
        navActive = false   // new destination — old figures are stale until the next updateNavInfo
        forgetLastManeuver()
        if (_state.value == DashState.READY || _state.value == DashState.STREAMING) {
            scope.launch(Dispatchers.IO) {
                socket?.send(liveRouteCard(projectionOn = true))
            }
        }
    }

    fun disconnect() {
        // Set FIRST, before anything else — see this flag's own kdoc: closing the socket below
        // unblocks rxJob's blocking receive() with an OS-level exception, and its catch block
        // checks this to know the error is an expected side effect of OUR OWN close(), not a real
        // link failure, so it doesn't call fail() and stomp the IDLE state this function sets below.
        disconnecting = true
        // Only the first of stop and release has anything to end.
        tellDisconnect(socket != null, sessionJob != null, _state.value)
        // Cancel the session coroutine FIRST so it can't race past auth and flip state to
        // READY after we tear down (which would re-trigger streaming on a dead socket).
        sessionJob?.cancel(); sessionJob = null
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel(); navInfoJob?.cancel()
        cardsJob?.cancel()
        statsJob?.cancel()
        navActive = false
        forgetLastManeuver()
        socket?.let { sock ->
            val goodbye = Runnable {
                runCatching { sock.send(DashCommands.projectionStop()) }
                runCatching { sock.send(DashCommands.projectionOff()) }
                // Extended exit sequence documented by another open implementation (see
                // DashCommands.exitBack's doc) — best-effort, additive, unverified on real hardware.
                // Meant to give the dash a cleaner exit-to-menu instead of possibly staying "hung"
                // until a power-cycle; each send is independently guarded so one failing never blocks
                // the rest of teardown.
                runCatching { sock.send(DashCommands.exitBack()) }
                repeat(4) { runCatching { sock.send(DashCommands.exitProjectionIdle()) } }
                repeat(2) { runCatching { sock.send(DashCommands.exitSessionBoundary()) } }
                sock.close()
            }
            // The rider tapping "disconnect" calls this on the main thread, where Android refuses network: every
            // send above failed with NetworkOnMainThreadException and the dash never heard the session was over
            // (observed in ride logs). There the goodbye goes out on a thread of its own,
            // waited for briefly so the socket is closed before a next connection opens its own.
            val mainLooper = android.os.Looper.getMainLooper()
            if (mainLooper != null && android.os.Looper.myLooper() == mainLooper) {
                Thread(goodbye, "DashGoodbye").apply { isDaemon = true; start() }.join(GOODBYE_WAIT_MS)
            } else {
                goodbye.run()
            }
            logStats(end = true)
        }
        socket = null
        readings?.flush()
        _state.value = DashState.IDLE
        Log.i(TAG, "Disconnected")
    }

    // ── Internal ──────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught") // any failure ends this session in ERROR; cancellation is handled above
    private suspend fun runSession(mine: Int, ssid: String, network: android.net.Network? = null) {
        // _state is already CONNECTING — set synchronously by connect() before this coroutine
        // was even launched, so the re-entrancy guard there can't race.
        // A session coroutine that connect() has already replaced opens no socket (it would bind the
        // same ports as the current one).
        if (!isCurrent(mine)) return
        try {
            val sock = openSockets(mine, ssid, network) ?: return
            if (!authenticate(sock, mine, ssid)) return
            enterNavMode(sock)
            // READY is this session's to say only while it is the current one; a replaced session's nav
            // entry went out on a socket already closed and must not move the new session along.
            if (isCurrent(mine)) {
                _state.value = DashState.READY
                Log.i(TAG, "READY ✓")
                com.reau.app.data.RideDiagnostics.log("session", "nav-mode kick sent — READY (waiting for video pipeline)")
            }
        } catch (e: CancellationException) {
            // connect() or disconnect() ended this coroutine on purpose: no failure to report.
            Log.i(TAG, "Session coroutine cancelled (replaced or disconnected)")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Session error", e)
            fail(NetworkBlock.describe(e), mine) // an EPERM says the network was blocked
        }
    }

    /**
     * The session's sockets, with the receive loop, the status heartbeat and the stats line started on them; null
     * after a failure already said through [fail] (the ports in use), or for a session connect() has replaced
     * meanwhile, whose sockets are closed again at once.
     */
    private fun openSockets(mine: Int, ssid: String, network: android.net.Network?): DashSocket? {
        val sock = try {
            socketFactory(network)
        } catch (e: java.net.BindException) {
            fail("Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})", mine)
            null
        }
        return when {
            sock == null -> null
            !isCurrent(mine) -> {
                sock.close()
                null
            }
            else -> {
                socket = sock
                sock.stats = stats
                linkNetwork = network
                linkHistory.clear()
                linkBaselineLogged = false
                launchStatsLine()
                auth = DashAuth(ssid)
                authConfirmed = false
                authRetries = 0
                loggedFirstAck = false
                aliveWithoutAck = false
                streamingStartMs = 0L
                lastRxMs = 0L
                notices0904 = 0
                notices0906 = 0
                // RX loop MUST be running before the burst (early pubkey + no ICMP).
                launchReceiveLoop(sock, mine)
                // 1 Hz status heartbeat throughout the session.
                launchStatusHeartbeat(sock, mine)
                sock
            }
        }
    }

    /** The initial burst and the wait for the dash's 07 01 01; false after a timeout, already said through [fail]. */
    private suspend fun authenticate(sock: DashSocket, mine: Int, ssid: String): Boolean {
        _state.value = DashState.AUTHENTICATING
        Log.i(TAG, "Sending initial burst…")
        for (pkt in DashCommands.initialBurst(HOSTNAME, status = phoneStatusProvider())) {
            sock.send(pkt)
            delay(BURST_PAUSE)
        }
        com.reau.app.data.RideDiagnostics.log("auth", "initial burst sent — waiting up to ${AUTH_TIMEOUT}ms for 07 01 01")

        Log.i(TAG, "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…")
        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT
        while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)

        if (!authConfirmed) {
            fail(authTimeoutReason(heardFromDash = lastRxMs > 0L, ssid = ssid), mine)
        } else {
            Log.i(TAG, "Authenticated ✓")
            com.reau.app.data.RideDiagnostics.log("auth", "authenticated (07 01 01) — entering nav mode")
        }
        return authConfirmed
    }

    /**
     * Nav entry in the exact phone order (the observed nav-open order):
     *   route-card ×4 (establishes destination) → projectionFrame
     *   → navStart once → route-card confirmation.
     */
    private suspend fun enterNavMode(sock: DashSocket) {
        sock.send(DashCommands.navContext()); delay(40)
        sock.send(DashCommands.emptyLists()); delay(40)

        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.navPlaceholder()); delay(10)
        sock.send(DashCommands.navStart()); delay(40)                 // start of navigation, ONCE
        sock.send(DashCommands.routeCard(destinationName, projectionOn = true))
        sock.send(DashCommands.zoomIcons()) // the "-" and "+" by the dash's ZOOM, at a route's start
        Log.i(TAG, "Nav mode kick sent")
    }

    @Suppress("TooGenericExceptionCaught") // a bad packet read ends or skips one turn of the loop, logged
    private fun launchReceiveLoop(sock: DashSocket, mine: Int) {
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = try {
                    sock.receiveBlocking() // already on IO: no context switch per packet
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Link dropped (EBADF/ENETUNREACH) — end the loop cleanly instead of crashing the
                    // app. Route through fail() (not just onError) so _state flips to ERROR right
                    // away: without it, a socket death during CONNECTING/AUTHENTICATING/READY (before
                    // the STREAMING-only RX watchdog would ever notice) left the state stuck, and the
                    // only backstop left was the 2-minute give-up timer instead of the retry logic
                    // that's keyed off DashState.ERROR. fail() itself no-ops when this exception is
                    // just our own disconnect() closing the socket to unblock this receive() — see
                    // its kdoc on `disconnecting`.
                    if (disconnecting) Log.i(TAG, "RX loop stopped — our own disconnect closed the socket: ${e.message}")
                    else Log.w(TAG, "RX loop stopped — socket error: ${e.message}")
                    fail("Lost connection to dash — socket error: ${e.message}", mine)
                    break
                } ?: continue
                lastRxMs = System.currentTimeMillis()
                dispatchIncoming(pkt, sock)
            }
        }
    }

    // Internal (not private) so DashSessionTelemetryTest can feed it a hand-built raw TLV packet
    // directly, without needing a real handshake/dash peer.
    @Suppress("TooGenericExceptionCaught") // the button handler must never end the receive loop
    internal fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val tlvs = K1GPacket.parseIncoming(pkt)
        // Read once, counted from the same list (the socket's stats read the packet a second time).
        sock.stats?.received(tlvs, System.currentTimeMillis())
        // No packet bytes to logcat (the detailed log's DashDump shows them, behind its switch).
        for (tlv in tlvs) {
            // The screen "Dados da moto" keeps what the dash says, but not the key exchange (07) nor the buttons (09 00).
            val kept = tlv.type != 0x07 && tlv.type != 0x0F && !(tlv.type == 0x09 && tlv.sub == 0x00)
            if (kept) readings?.onValue(tlv.type, tlv.sub, tlv.value)
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        Log.i(TAG, "Got RSA pubkey — sending the session key")
                        com.reau.app.data.RideDiagnostics.log("auth", "RSA pubkey received → sending session key")
                        sock.send(ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true }
                    AuthEvent.Rejected -> {
                        authRetries++
                        Log.w(TAG, "Auth rejected — retry #$authRetries")
                        com.reau.app.data.RideDiagnostics.log("auth", "REJECTED — retry #$authRetries")
                        auth?.reset()
                        if (authRetries <= 5) sock.send(DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── 09 06 55: per-IDR frame-decoded notify → mandatory reply ──
            if (tlv.type == 0x09 && tlv.sub == 0x06 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                if (!loggedFirstAck && confirmsFirstFrame(_state.value)) {
                    loggedFirstAck = true
                    com.reau.app.data.RideDiagnostics.log("dash", "dash DECODED first IDR (09 06 55) — video accepted ✓")
                } else if (!loggedFirstAck) {
                    com.reau.app.data.RideDiagnostics.log("dash", "dash sent 09 06 55 while ${_state.value}, before any video: not the first frame")
                }
                decodeAcks.incrementAndGet()
                noticeLine("09 06 55", ++notices0906, "answered with 06 11")
                sock.send(DashCommands.frameDecodedIdr())
                continue
            }
            // ── 09 04 55: P-frame decoded → reply ──
            if (tlv.type == 0x09 && tlv.sub == 0x04 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                decodeAcks.incrementAndGet()
                noticeLine("09 04 55", ++notices0904, "answered with 06 12")
                sock.send(DashCommands.frameDecodedP())
                continue
            }
            // ── 09 00: button / joystick event → echo ack + hand it on ──
            if (tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()  // 0900 0001 <code>
                Dbg.i(TAG) { "JOYSTICK 09 00 → code 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}" }
                repliesToButton(btn).forEach { sock.send(it) } // the echo, and the zoom icons after a zoom
                val event = com.reau.app.dash.protocol.DashButtonEvent.fromByte(btn)
                // Handed on right here, on this receive thread: the main thread was
                // one more hop, and it waits whenever the REau screen is busy. The handler only maps the press and
                // queues it to Android Auto's own send thread; a failure in it must not end this loop.
                try {
                    onButton?.invoke(event)
                } catch (e: Exception) {
                    Log.w(TAG, "button handler failed: $e")
                    com.reau.app.data.RideDiagnostics.error("error", "button handler failed: $e")
                }
                continue
            }
            // ── 0F: vehicle-secure telemetry (AES-256-CBC under the session key,
            //    IV = first 16 bytes). This is the dash's instrument-cluster data
            //    (likely trip/odo/fuel/speed/temp). The better-dash reference only
            //    logs these as ciphertext — we actually DECRYPT with our session key
            //    and hand the plaintext on with the telemetry. Not to logcat (the
            //    detailed log has the packet). ──
            if (tlv.type == 0x0F) {
                val key = auth?.sessionKey
                val plain = key?.let { aesDecryptCbc(tlv.value, it) }
                // 0F04 (safe mode) is the one 0F value that is not encrypted; the rest are kept decrypted, or not at all.
                (if (tlv.sub == 0x04) tlv.value else plain)?.let { readings?.onValue(tlv.type, tlv.sub, it) }
                _telemetry.tryEmit(com.reau.app.dash.protocol.TelemetryEvent(System.currentTimeMillis(), tlv.type, tlv.sub, tlv.value, plain))
                continue
            }
            // ── 0C xx: dash → app telemetry (trip/odo/fuel/temp) ──
            if (tlv.type == 0x0C) {
                _telemetry.tryEmit(com.reau.app.dash.protocol.TelemetryEvent(System.currentTimeMillis(), tlv.type, tlv.sub, tlv.value))
                continue
            }
            // Every OTHER incoming event goes on with the telemetry (not in full to logcat; the detailed log's
            // DashDump shows it).
            _telemetry.tryEmit(com.reau.app.dash.protocol.TelemetryEvent(System.currentTimeMillis(), tlv.type, tlv.sub, tlv.value))
        }
    }

    /**
     * One line per 09 04 55 / 09 06 55 that arrives: which notice, how many so far in this session, how long
     * into the stream, in which state, and what REau did about it. The 09 06 55 come in pairs, the first about 8 s into the
     * stream (the first keyframe shown); the 09 04 55 are rare.
     */
    private fun noticeLine(name: String, n: Int, did: String) {
        val into = if (streamingStartMs > 0L) "%.1f s into the stream".format((System.currentTimeMillis() - streamingStartMs) / 1000.0) else "not streaming"
        com.reau.app.data.RideDiagnostics.log("dash", "notice $name #$n, $into, ${_state.value}: $did")
    }

    private fun launchStatusHeartbeat(sock: DashSocket, mine: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                val status = phoneStatusProvider()
                runCatching { sock.send(DashCommands.heartbeat(weather = weatherProvider(), status = status, call = callNow())) }
                stats.heartbeat() // the 1 Hz status heartbeat
                // The second 1 Hz status packet the dash accepts is the weather's: the heartbeat above already carries both
                // fields (06 08, 06 10), so it is not sent.
                // Keep the dash clock correct — it only shows what the phone feeds it.
                if (n % 30 == 0) runCatching { sock.send(DashCommands.timeSync()) }
                // The bike's data, asked for again every 15 minutes while connected (the first time is the initial burst).
                if (dataRefreshDue(n, _state.value)) {
                    runCatching { sock.send(DashCommands.versionQuery()) }
                    com.reau.app.data.RideDiagnostics.log("dash", "bike data asked again (0A 02, ${n / DATA_REFRESH_TICKS} of every 15 min)")
                }
                sampleLink(n)
                n++
                // RX watchdog: once the dash has acked at least one frame, a long silence means it
                // dropped (out of range / off). UDP gives no error, so detect it here and fail the
                // session instead of streaming into the void while the UI still says "connected".
                val rxSilentTooLong = lastRxMs > 0L && System.currentTimeMillis() - lastRxMs > RX_WATCHDOG_MS
                val streamingAlive = _state.value == DashState.STREAMING && (loggedFirstAck || aliveWithoutAck)
                if (streamingAlive && rxSilentTooLong) {
                    val silent = (System.currentTimeMillis() - lastRxMs) / 1000
                    Log.w(TAG, "RX watchdog: no dash packets for ${silent}s — link lost")
                    com.reau.app.data.RideDiagnostics.error("error", "RX watchdog: dash silent ${silent}s → link lost")
                    logLinkAtFailure()
                    com.reau.app.data.ProjectionSummary.current.watchdog("dash-rx")
                    watchdogFailMs = System.currentTimeMillis()
                    fail("Dash stopped responding — connection lost", mine)
                    break
                }
                // First-ack watchdog: a real gap the RX watchdog above can NEVER catch, because it
                // requires loggedFirstAck to already be true — i.e. it only detects a link that
                // WORKED and then died, never one that never worked in the first place. Confirmed
                // live in a ride log: the dash WiFi cycled through
                // REQUESTING → unavailable → retry for over 6 MINUTES straight (never once reaching
                // CONNECTED again after an initial blip), while this session's socket — still bound
                // to the stale Network from before the blip — kept accepting sendto() calls without
                // throwing (no exception ⇒ no fail() from the RX loop's own catch block either).
                // _state sat in STREAMING the entire time with zero feedback to the rider: the phone
                // showed "transmitting" while the physical dash showed nothing but its own idle
                // compass-ring screen. See firstAckTimedOut's own doc for the pure check.
                val firstAck = firstAckVerdict(
                    loggedFirstAck || aliveWithoutAck, streamingStartMs, System.currentTimeMillis(), FIRST_ACK_TIMEOUT_MS,
                    lastRxMs, RX_WATCHDOG_MS,
                )
                if (firstAck == FirstAck.ALIVE_WITHOUT_ACK) {
                    aliveWithoutAck = true
                    com.reau.app.data.RideDiagnostics.warn(
                        "dash",
                        "no 09 06 55 after ${FIRST_ACK_TIMEOUT_MS / 1000}s, but the dash answers: the stream goes on (the RX watchdog watches it)",
                    )
                }
                if (firstAck == FirstAck.DEAD) {
                    val waited = (System.currentTimeMillis() - streamingStartMs) / 1000
                    Log.w(TAG, "First-ack watchdog: dash never confirmed decoding a frame after ${waited}s — link likely dead")
                    com.reau.app.data.RideDiagnostics.error("error", "first-ack watchdog: no decode ack after ${waited}s → link likely dead")
                    logLinkAtFailure()
                    com.reau.app.data.ProjectionSummary.current.watchdog("dash-first-ack")
                    watchdogFailMs = System.currentTimeMillis()
                    fail("Dash never showed the video — check the WiFi link", mine)
                    break
                }
                delay(1_000)
            }
        }
    }

    /**
     * One 1 Hz heartbeat tick of the Wi-Fi link: a sample every [LinkReport.SAMPLE_EVERY_TICKS] s into the minute's
     * history, and, every [LinkReport.LOG_EVERY_TICKS] s, a line of the log with the moment and the trend (the ride's
     * baseline, so a drop can be told from a link that was weak all along). Only while streaming: before that the
     * link is the connection's own business.
     */
    private fun sampleLink(tick: Int) {
        if (_state.value != DashState.STREAMING || tick % LinkReport.SAMPLE_EVERY_TICKS != 0) return
        val sample = linkProbe(linkNetwork)
        linkHistory.add(sample)
        if (!linkBaselineLogged || tick % LinkReport.LOG_EVERY_TICKS == 0) {
            linkBaselineLogged = true
            com.reau.app.data.RideDiagnostics.log("link", "${LinkReport.describe(sample)} | ${LinkReport.trend(linkHistory.snapshot())}")
        }
    }

    /** The Wi-Fi link when a watchdog fires: the moment and the minute before it (the failure's own context). */
    private fun logLinkAtFailure() {
        com.reau.app.data.RideDiagnostics.log("link", LinkReport.atFailure(linkProbe(linkNetwork), linkHistory.snapshot()))
    }

    /** One dash-stats line every [statsEveryMs] while the session lives. */
    private fun launchStatsLine() {
        statsJob?.cancel()
        statsWindowStartMs = System.currentTimeMillis()
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(statsEveryMs)
                logStats(end = false)
            }
        }
    }

    private fun logStats(end: Boolean) {
        val now = System.currentTimeMillis()
        val line = stats.windowLine(now - statsWindowStartMs, now)
        statsWindowStartMs = now
        com.reau.app.data.RideDiagnostics.log("dash", if (end) "$line (session end)" else line)
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(DashCommands.projectionFrame())
                stats.heartbeat()
                delay(PROJ_HB_MS)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(liveRouteCard(projectionOn = true))
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchNavInfo() {
        navInfoJob?.cancel()
        navInfoJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navActive) {
                    socket?.send(
                        DashCommands.activeNavPacket(
                            maneuver = ringGlyph(),
                            primaryDist = navPrimaryDist,
                            primaryUnit = navPrimaryUnit,
                            totalDist = navTotalDist,
                            totalUnit = navTotalUnit,
                            road = navRoad,
                            decimalComma = decimalComma(),
                            proximity = proximityBar(),
                            nextManeuver = nextShown(),
                        )
                    )
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    /** The music and call cards, every second while streaming (as the route card). */
    private fun launchCards() {
        cardsJob?.cancel()
        cardsJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                sendCards()
                delay(ROUTE_CARD_MS)
            }
        }
    }

    /**
     * Internal (not private) so DashSessionTest can drive it directly without a real socket/dash
     * peer — same "not private, tests only" reasoning as [dispatchIncoming].
     *
     * Idempotent per failure episode: a real double-fail sequence was found in a
     * ride log — the RX watchdog (launchStatusHeartbeat) calls this, cancels [rxJob],
     * then closes [socket]. [Job.cancel] is cooperative and does nothing to a coroutine that's
     * currently blocked inside a SYNCHRONOUS call — [rxJob] is blocked in [DashSocket.receive]
     * exactly at that moment, so it doesn't notice the cancellation; closing the socket's
     * underlying fd is what actually unblocks it, with an OS-level error (seen in use: `recvfrom
     * failed: EBADF`) rather than a CancellationException. [launchReceiveLoop]'s catch block then
     * calls THIS function a second time with that confusing low-level message — which, before this
     * guard, overwrote the rider-facing error text with "EBADF (Bad file
     * descriptor)" instead of the real, already-correct "Dash stopped responding" diagnosis, and
     * fired [onError] twice for one underlying event. Guarding on [_state] already being ERROR
     * makes every path into this function safe to call more than once — the first call's diagnosis
     * (and its single [onError] callback) always wins.
     *
     * Also guarded on [disconnecting] — the SAME blocked-in-`receive()`-unblocked-by-`close()` race
     * described above, but racing against a clean [disconnect()] instead of a second real failure:
     * closing the socket to tear down deliberately produces the identical OS-level exception, and
     * without this guard it could reach here and overwrite disconnect()'s own `_state = IDLE` write
     * with ERROR. Found live: `DashSessionFakeDashTest`'s teardown test
     * failed intermittently with "expected IDLE but was ERROR" from disconnect() racing itself, no
     * other test or stale packet involved.
     *
     * [generation] says which session reports the failure (the number connect() gave
     * it; the session's own coroutines pass theirs). A failure of a session that connect() has since replaced is
     * ignored: its socket is already closed, and the current session's is not its to close. The default is the
     * current session, for the watchdogs' tests that drive this directly. Every coroutine of the failed session is
     * cancelled, the session coroutine, the stats line and the cards included: before, those three outlived the
     * failure (the stats line kept writing after ERROR, and the session coroutine could fail or READY a later one).
     */
    internal fun fail(msg: String, generation: Int = sessionGeneration) {
        if (generation != sessionGeneration) {
            Log.i(TAG, "fail() ignored — from a session connect() has since replaced (#$generation, now #$sessionGeneration): $msg")
            com.reau.app.data.RideDiagnostics.log("session", "a failure of an earlier session ignored: $msg")
            return
        }
        if (disconnecting) {
            Log.i(TAG, "fail() ignored — disconnect() already tore this session down cleanly: $msg")
            return
        }
        if (_state.value == DashState.ERROR) {
            Log.w(TAG, "fail() called again while already ERROR (ignored, real diagnosis already reported): $msg")
            return
        }
        Log.e(TAG, "ERROR — $msg")
        com.reau.app.data.RideDiagnostics.error("error", "session fail: $msg")
        rxJob?.cancel(); heartbeatJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); navInfoJob?.cancel()
        // The 5 s stats line and the cards loop have nothing to report once the session is down; left running they
        // wrote a `dash-stats ... tx=0` line every 5 s for as long as the dash stayed away.
        statsJob?.cancel(); cardsJob?.cancel()
        // The session coroutine too (it may be the caller: then it returns right after this, and the
        // cancellation only ends it at its next suspension, which never comes).
        sessionJob?.cancel()
        socket?.close(); socket = null
        readings?.flush()
        _state.value = DashState.ERROR
        onError?.invoke(msg)
    }

    companion object {
        /**
         * Whether the dash's keyframe-decoded notice (09 06 55) confirms the first frame: only while
         * streaming, when a keyframe was really sent. One before (seen on the phone, before any authentication) is
         * still answered and counted, but neither logs "video accepted" nor tells the watchdog the video got through.
         */
        internal fun confirmsFirstFrame(state: DashState): Boolean = state == DashState.STREAMING

        /**
         * Why the authentication ran out. Only a dash that answered and still did not accept points at the SSID (the
         * dash checks it inside the encrypted handshake); not a single packet means the dash is not there (seen with
         * the bike's Wi-Fi already gone, when the log used to send the rider to check the SSID).
         */
        /** The log's line of a [connect]: the dash's name masked (it ties a log to one bike). */
        internal fun connectLine(ssid: String, network: Any?): String =
            "connect() — ssid='${DashWifiManager.maskSsid(ssid)}' network=$network"

        internal fun authTimeoutReason(heardFromDash: Boolean, ssid: String): String =
            if (heardFromDash) "Auth timed out — the dash answered but never accepted (no 07 01 01). Check SSID matches '${DashWifiManager.maskSsid(ssid)}'."
            else "Auth timed out — not a single packet from the dash (bike off, or its Wi-Fi gone?)."

        /** Glyph for the dash's maneuver circle: the real one, or the code that hides it. */
        internal fun bubbleManeuver(circleEnabled: Boolean, maneuver: Int): Int =
            if (circleEnabled) maneuver else DashCommands.NAV_MANEUVER_NONE

        /**
         * What the ring shows for [incoming] while guiding: [incoming] when the dash can draw it (arrivals and the
         * rerouting compass included), else the last one it could draw ([lastVisible]), else straight on.
         */
        internal fun ringManeuver(incoming: Int, lastVisible: Int?): Int =
            if (DashManeuverGlyphs.isVisible(incoming)) incoming else lastVisible ?: DashManeuverGlyphs.STRAIGHT

        /**
         * What a joystick press gets back: its echo, and after a zoom or recentre the zoom state with both icons.
         */
        internal fun repliesToButton(code: Byte): List<ByteArray> =
            if ((code.toInt() and 0xFF) in ZOOM_CODES) listOf(DashCommands.buttonAck(code), DashCommands.zoomIcons())
            else listOf(DashCommands.buttonAck(code))

        /** The dash's zoom (0x13, 0x14) and recentre (0x15) codes. */
        private val ZOOM_CODES = setOf(0x13, 0x14, 0x15)

        /** The proximity bar from which the turn is more than 100 m away (5 and 6). */
        private const val FAR_BAR = 5
        private const val TAG           = "DashSession"
        private const val AUTH_TIMEOUT  = 15_000L
        private const val BURST_PAUSE   = 20L
        // Projection control keep-alive (a TLV, NOT the video). Now that the video cadence is
        // motion-adaptive (250 ms active / 500 ms idle), this is a STEADY keep-alive pinned
        // to the fast video interval — i.e. always sent at least as often as a video frame, never
        // slower. That's the safe direction (the dash's projection state never starves). A protocol trace
        // will tell whether this control packet must follow the
        // video rate; if it can run slower, raise this. Was 42 ms (24 Hz) to match the old fixed loop.
        private const val PROJ_HB_MS     = 250L   // 4 Hz — matches FRAME_MS_ACTIVE (the fastest video rate)
        private const val ROUTE_CARD_MS  = 1_000L // 1 Hz keep-alive
        // While streaming, the dash sends something several times a second (its 1 Hz 0101 heartbeat and the 0C
        // telemetry; frame acks only for the first keyframe). If it all STOPS for this long the dash is gone (out of
        // range / powered off) — but over UDP there's no socket error, so without this watchdog the app streams into
        // the void showing "connected". Checked once a second by the status heartbeat, so it fires 4-5 s in.
        // 4 s, not 6 s: in the ride logs (157 files, 27,192 gaps while
        // streaming) the longest silence with the dash still there was 2.96 s; every one from 3.47 s up was a real
        // loss (blocked network, Android Auto restarting, the bike off).
        private const val RX_WATCHDOG_MS = 4_000L
        /** The bike's data is asked for again every 15 minutes while connected: 900 ticks of the 1 Hz heartbeat. */
        const val DATA_REFRESH_TICKS = 900

        /**
         * Whether heartbeat tick [tick] (counted from the start of the session; tick 0 is the burst's own ask) is one for the
         * bike's data, and the dash is connected and past the authentication.
         */
        internal fun dataRefreshDue(tick: Int, state: DashState): Boolean =
            tick > 0 && tick % DATA_REFRESH_TICKS == 0 && (state == DashState.READY || state == DashState.STREAMING)

        // How long a disconnect on the main thread waits for the goodbye to leave: a handful of UDP sends.
        private const val GOODBYE_WAIT_MS = 400L
        // How long STREAMING can go without the dash EVER confirming it decoded a frame before
        // this is treated as a dead link — see firstAckTimedOut's own doc. Matches AUTH_TIMEOUT's
        // own order of magnitude (a real dash typically acks its first
        // decoded frame within 1-8s of entering STREAMING) with generous headroom for normal
        // startup jitter, while still being short enough to give the rider real feedback (and let
        // the caller's retry/give-up logic actually run) instead of sitting silent for
        // however long the underlying WiFi instability lasts.
        private const val FIRST_ACK_TIMEOUT_MS = 15_000L
        private const val STATS_EVERY_MS = 5_000L
        /** The device name the dash shows on its "Connected to X" screen. */
        internal const val HOSTNAME       = "REau"
        /** The route-card title while nothing else was set: what the dash is showing. */
        internal const val DEFAULT_DESTINATION = "REau"

        /**
         * Pure decision core of the first-ack watchdog above — separate from the RX watchdog
         * because that one is gated on [loggedFirstAck] already being true (a link that worked and
         * then died). This one is the opposite case: STREAMING started, but the dash has NEVER once
         * confirmed decoding a frame. Kept as a companion function (like [aesDecryptCbc]) so
         * DashSessionFirstAckWatchdogTest can exercise the boundary without a live CoroutineScope.
         */
        internal fun firstAckTimedOut(loggedFirstAck: Boolean, streamingStartMs: Long, nowMs: Long, timeoutMs: Long): Boolean =
            !loggedFirstAck && streamingStartMs > 0L && nowMs - streamingStartMs > timeoutMs

        /** What the first-ack watchdog makes of the moment: wait, a dead link, or a dash that answers but sends no ack. */
        internal enum class FirstAck { WAIT, DEAD, ALIVE_WITHOUT_ACK }

        /**
         * [firstAckTimedOut], and then whether the dash still answers ([lastRxMs] within [rxSilenceMs] of [nowMs]): the
         * stale-socket case the watchdog is for hears nothing from the dash; a dash that answers and only sends no
         * 09 06 55 is showing the video (a ride: the map on the dash while the session was ended every 15 s).
         */
        @Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
        internal fun firstAckVerdict(
            confirmed: Boolean,
            streamingStartMs: Long,
            nowMs: Long,
            timeoutMs: Long,
            lastRxMs: Long,
            rxSilenceMs: Long,
        ): FirstAck = when {
            !firstAckTimedOut(confirmed, streamingStartMs, nowMs, timeoutMs) -> FirstAck.WAIT
            lastRxMs > 0L && nowMs - lastRxMs <= rxSilenceMs -> FirstAck.ALIVE_WITHOUT_ACK
            else -> FirstAck.DEAD
        }

        /**
         * AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key.
         * Doesn't touch instance state — kept in the companion (like [DashCommands]) so
         * DashSessionCryptoTest can exercise it directly without constructing a DashSession
         * (which needs a live CoroutineScope).
         */
        internal fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
            if (ivAndCt.size <= 16) return null
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
            )
            cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
        }.getOrNull()
    }
}

/**
 * The projection's stop and its release both end the dash session, and every stop left this line twice.
 * Told only by a disconnect that finds a socket, a session coroutine or a state other than idle to end.
 */
internal fun tellDisconnect(hasSocket: Boolean, hasJob: Boolean, state: DashState) {
    if (hasSocket || hasJob || state != DashState.IDLE) {
        com.reau.app.data.RideDiagnostics.log("session", "disconnect() — explicit, not a link drop")
    }
}
