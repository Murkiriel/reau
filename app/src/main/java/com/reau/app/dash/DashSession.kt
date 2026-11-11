// [stats] counts what the session and the dash exchange (at the socket, and the heartbeats here), one dash-stats line
// every statsEveryMs. The dash's name goes to the log masked.
package com.reau.app.dash

import android.util.Log
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.dash.nav.DashManeuverGlyphs
import com.reau.app.util.Dbg
import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.util.FailureStreak
import com.reau.app.util.MonotonicClock
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
 * The RX loop runs the WHOLE time, answering the key exchange, the dash's 09 06 / 09 04 notices (answered as the
 * dash expects; they are no proof of a decoded frame, see [clockNoticeLine]) and 09 00 button events.
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
    /** The session's waits; a test shortens them (27/10/2026 review: the fake-dash tests took 86 s on the real ones). */
    private val timing: Timing = Timing(),
    /** The experimental switch of the bind retries ([BindRetry]), read at each try to open the sockets; off, a refused bind goes straight to [onBindResult]. */
    private val bindRetry: () -> Boolean = { false },
) {
    /**
     * The waits of a session: [keyPatienceMs] for the dash's key before the greeting goes again, [rxWatchdogMs] of silence before the
     * dash is taken as lost, and [watchdogTickMs], the pace of the watchdogs' loop (the 1 Hz heartbeat). Every real caller uses the defaults.
     */
    data class Timing(
        val keyPatienceMs: Long = KEY_PATIENCE_MS,
        val rxWatchdogMs: Long = RX_WATCHDOG_MS,
        val watchdogTickMs: Long = WATCHDOG_TICK_MS,
        /** The wait between two tries of a refused bind ([BindRetry.PAUSE_MS]). */
        val bindRetryPauseMs: Long = BindRetry.PAUSE_MS,
    )

    /** What this session and the dash exchanged. */
    val stats = DashStats(onFirst = { com.reau.app.data.RideDiagnostics.log("dash", it) })
    private var statsJob: Job? = null
    @Volatile private var trailLoggedMs = Long.MIN_VALUE / 2
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

    @Volatile private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0
    // The dash's own 10 s mark (09 06 55) seen while authenticating: when, counted from the first burst (-1 = not yet), and whether it
    // has made the burst go out again (once a session) and is waiting to be sent by the authentication loop.
    @Volatile private var burstStartMs = 0L
    @Volatile private var clockNoticeAfterMs = -1L
    @Volatile private var resendOnNotice = false
    // The counter of the dash's first heartbeat (0101) of this session and when it came (-1: none yet): how long the dash has been on ([DashReadiness]).
    @Volatile private var firstHeartbeat = -1
    @Volatile private var firstHeartbeatAtMs = 0L
    // The 10 s mark came before the stream (AUTHENTICATING or READY): the dash was alive then; startStreaming() counts it.
    @Volatile private var clockNoticeBeforeStream = false
    private val linkNotice = LinkNotice()
    private var resentOnNotice = false
    // Wall-clock of the last packet received from the dash — fed to the RX watchdog.
    @Volatile private var lastRxMs = 0L

    // Packets from the dash since this session opened: what tells a weak link from a dash that did not accept ([authTimeoutReason]).
    @Volatile private var rxPackets = 0
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

    // Wall-clock of entering STREAMING — see firstAckTimedOut's own doc for the real gap this closes.
    @Volatile private var streamingStartMs = 0L
    // When this session's sockets were opened (0: not yet), for the line of what was sent before the dash went quiet.
    @Volatile private var socketsOpenedMs = 0L
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

    // The exit sequence of the disconnect going on (or the last), to log what the dash answers to it; see [ExitSequence].
    @Volatile private var exitSequence: ExitSequence? = null

    // Which connect() the running session belongs to. Every coroutine a session launches
    // carries the number it was launched with, and fail()/READY from a session that is not the current one are
    // ignored: before this, a session that failed during authentication (its socket closed by fail()) kept its
    // runSession coroutine waiting for 07 01 01 for up to AUTH_TIMEOUT, and when a new connect() had meanwhile
    // started the next session, that leftover's own fail() closed the new session's socket and put it in ERROR, or
    // its READY went out on a socket already closed.
    @Volatile private var sessionGeneration = 0

    var onButton: ((com.reau.app.dash.protocol.DashButtonEvent) -> Unit)? = null

    /** The maneuver ring (with its switch off) was shown or hidden, by a press or by the screen: a line saying which and why, for the debug text. */
    var onRingChange: ((String) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    /**
     * Told how binding the session's sockets to the dash's network went: the network and whether Android refused it (EPERM, `true`) or the
     * sockets opened (`false`). A refused network is no good to try again (31/10/2026: ten times in 4.5 minutes), so whoever holds the
     * network drops it and asks for another. Called before the failure of a refusal is reported.
     */
    var onBindResult: ((network: android.net.Network?, refused: Boolean) -> Unit)? = null

    private var notices0904 = 0
    private var notices0906 = 0

    // The title of the card with no route: always REau (a card per app was written and never wired; taken out 27/10/2026).
    private val destinationName: String = DEFAULT_DESTINATION

    // Launched on the IO threads and cancelled by disconnect(), which runs on the service's worker or the IO scope: volatile, so
    // disconnect() sees a job that was just launched.
    @Volatile private var sessionJob: Job? = null
    @Volatile private var rxJob: Job? = null
    @Volatile private var projHbJob: Job? = null
    @Volatile private var routeCardJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var navInfoJob: Job? = null
    @Volatile private var cardsJob: Job? = null

    /**
     * The live turn-by-turn figures pushed to the dash at ~1 Hz, as one value: the writer replaces it whole and a reader
     * takes it once, so a card never mixes the distance of one update with the unit of the next (a "10 m" for a second
     * where it was "1 km" turning; audit of 20/10/2026). Null: no guidance (the nav packets stop).
     */
    internal data class NavFigures(
        val maneuver: Int,
        val primaryDist: Int,
        val primaryUnit: Int,
        val totalDist: Int,
        val totalUnit: Int,
        val eta: String?,
        /** The street of the next maneuver, for the dash only; blank when Android Auto does not say it. */
        val road: String,
        /** The real time left and the maneuver after the next (always on). */
        val remaining: Long,
        val next: Int,
    )

    @Volatile private var nav: NavFigures? = null

    /** For the tests: the figures the cards are made from now. */
    internal val navNow: NavFigures? get() = nav
    /** What the route card says with no route (the joystick in the dash's map mode depends on it). */
    @Volatile var idleCard = DashIdleCard.PIN

    /**
     * Rider's choice (Settings, "Anel de manobras"): false keeps the dash's own maneuver circle hidden, while guiding and with no
     * route, except where the joystick shows it ([ringByPress]).
     */
    @Volatile var maneuverCircleEnabled = true
        set(value) {
            field = value
            if (!value) ringByPress.reset()
        }

    // With the switch off: the ring shown in the dash's map mode (so its sides keep coming) and hidden in its media mode.
    private val ringByPress = RingByPress()
    // A call, ringing or going on, always shows it: with the ring hidden the dash stops passing the map mode's joystick on, and the joystick is
    // what answers and ends the call ([callerName] is set while Android Auto says there is one).
    private val ringOn: Boolean
        get() = callerName != null || maneuverCircleEnabled || ringByPress.shown

    /**
     * With the info ring on in the settings ([ringCycleOn]), the dash's down button in its map mode ([RingCycle]) shows it and hides it; the
     * maneuver ring is not touched. [ringStep] is the choice (0 shown, 1 hidden; kept by [ringStepMemory] from one projection to the next)
     * and [infoInPlace] says whether the info ring sits in the place of the maneuver ring.
     */
    @Volatile var ringCycleOn = false
    @Volatile var infoInPlace = false
    @Volatile var ringStep = 0
        set(value) { field = RingCycle.valid(value) }
    @Volatile var ringStepMemory: RingStepMemory? = null

    /** Whether the dash's down button in its map mode is the info ring's switch: the rider has not given it another action ([com.reau.app.aa.JoystickBindings.infoRingControl]). */
    @Volatile var infoRingPress = true

    /** Whether the info ring is on the video now: its switch is on, the rider has not hidden it with the joystick and the screen is a navigation one. */
    val infoVisible: Boolean get() = ringCycleOn && RingCycle.info(ringStep) && !ringsAwayForScreen

    /**
     * The info ring stays off the dash's own app menu and the other apps' screens (the cropped picture of those screens has no room for
     * it): the rider's choice is kept as it is and comes back with the navigation. A screen not known yet hides nothing.
     */
    private val ringsAwayForScreen: Boolean
        get() = aaScreen == com.reau.app.aa.AaScreen.MENU || aaScreen == com.reau.app.aa.AaScreen.APPS

    /**
     * Whether the dash's maneuver ring is on the glass now: the rider's switch, or with it off the joystick's and the screen's choice,
     * or a call. The info ring on the right shows and hides by this same answer.
     */
    val ringVisible: Boolean get() = ringOn

    /**
     * The screen of Android Auto changed. With the maneuver switch off, Waze and Google Maps show the maneuver ring and any other screen hides
     * it (a screen not known yet changes nothing); the card goes at once. The info ring goes away on the menu and the apps and comes back, as
     * the rider left it, with the navigation ([ringsAwayForScreen]): that is on the video, with no card.
     */
    fun ringFollowsScreen(screen: com.reau.app.aa.AaScreen) {
        if (screenMovedRings(screen)) socket?.let { sock -> runCatching { sock.send(liveRouteCard(projectionOn = true)) } }
    }

    /** [ringFollowsScreen] without the card: true when the screen put the maneuver ring on or off the glass. */
    internal fun screenMovedRings(screen: com.reau.app.aa.AaScreen): Boolean {
        val awayBefore = ringsAwayForScreen
        aaScreen = screen
        if (ringCycleOn && awayBefore != ringsAwayForScreen) {
            val where = if (ringsAwayForScreen) "away" else "back (${if (RingCycle.info(ringStep)) "shown" else "hidden by the joystick"})"
            com.reau.app.data.RideDiagnostics.log("dash", "info ring $where for the screen of Android Auto ($screen)")
        }
        return ringAfterScreen(screen)
    }

    internal fun ringAfterScreen(screen: com.reau.app.aa.AaScreen): Boolean {
        val nav = when (screen) {
            com.reau.app.aa.AaScreen.NAV -> true
            com.reau.app.aa.AaScreen.MENU, com.reau.app.aa.AaScreen.APPS -> false
            com.reau.app.aa.AaScreen.UNKNOWN -> null
        }
        if (maneuverCircleEnabled || !ringByPress.screen(nav)) return false
        com.reau.app.data.RideDiagnostics.log("dash", "ring ${if (ringByPress.shown) "shown" else "hidden"} for the screen of Android Auto ($screen)")
        onRingChange?.invoke(com.reau.app.video.ButtonText.ringNote(ringByPress.shown, "screen $screen"))
        return true
    }

    /** The screen of Android Auto as last told by [ringFollowsScreen]: the rings are away on the menu and the apps. */
    @Volatile private var aaScreen = com.reau.app.aa.AaScreen.UNKNOWN

    /**
     * A press with [code]: the info ring's switch ([RingCycle]) when it is on and the screen is a navigation one (on the menu and the apps the
     * choice stands, so the navigation comes back as the rider left it), and, with the maneuver switch off, the maneuver ring following the mode
     * the code says. True when the maneuver ring changed (the card goes at once); the info ring is on the video and needs no card.
     */
    internal fun ringAfterPress(code: Int): Boolean {
        if (infoRingSwitchable && RingCycle.advancesOn(code)) switchInfoRing(code)
        return !maneuverCircleEnabled && ringByPress.press(code)
    }

    /** Whether a press of the down button may switch the info ring now: it is on, the button is its switch ([infoRingPress]) and the screen is a navigation one. */
    private val infoRingSwitchable: Boolean get() = ringCycleOn && infoRingPress && !ringsAwayForScreen

    /** The down button showed or hid the info ring; the choice is kept for the next projection. */
    private fun switchInfoRing(code: Int) {
        ringStep = RingCycle.next(ringStep)
        runCatching { ringStepMemory?.step = ringStep }
        // Said after the card is on the wire, as the maneuver ring's line is (the log's lock is shared with the video threads).
        infoSwitchLine = "info ring ${onOff(RingCycle.info(ringStep))} by the joystick (0x%02X)".format(java.util.Locale.US, code)
    }

    /** The ride log's line for the info ring's last switch, until the receive loop has said it; only that thread reads and clears it. */
    @Volatile private var infoSwitchLine: String? = null

    /** The ride log's line for a ring changed by a press, with how long after the press came off the socket the card was on the wire. */
    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun logRingByPress(code: Int, onTheWireMs: Long) {
        val state = if (ringByPress.shown) "shown" else "hidden"
        com.reau.app.data.RideDiagnostics.log(
            "dash", "ring $state by the joystick (0x%02X), on the wire +%d ms after the press came in".format(java.util.Locale.US, code, onTheWireMs),
        )
        onRingChange?.invoke(com.reau.app.video.ButtonText.ringNote(ringByPress.shown, "joystick 0x%02X".format(java.util.Locale.US, code)))
    }

    // The last maneuver of this guidance the dash could draw, but the rerouting
    // compass; and whether the ring is keeping it now (said once per stretch in the ride log).
    @Volatile private var lastVisibleManeuver: Int? = null
    @Volatile private var keepingManeuver = false

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
    ) {
        val shown = ringManeuver(maneuver, lastVisibleManeuver)
        if (shown != maneuver) {
            if (!keepingManeuver) {
                // Expected at the start of a guidance: Android Auto says "active" a few tens of ms before its first step
                // (22:27:07 on 19/10/2026: active at .143, the right turn at .191), and Waze's UNKNOWN on a stretch with no
                // turn. The ring shows the last turn it could draw, or straight on; the next drawable one replaces it.
                com.reau.app.data.RideDiagnostics.log(
                    "dash",
                    "nav: Android Auto's maneuver 0x%02X cannot be drawn (no step yet, or no turn ahead); the ring keeps 0x%02X"
                        .format(maneuver, shown),
                )
            }
            keepingManeuver = true
        } else {
            keepingManeuver = false
            if (maneuver != DashManeuverGlyphs.RECALCULATING) lastVisibleManeuver = maneuver
        }
        nav = NavFigures(shown, primaryDist, primaryUnit, totalDist, totalUnit, etaHHMM, road, remainingSeconds, nextManeuver)
    }

    /** For the tests: what the ring carries now (straight on with no guidance). */
    internal val ringNow: Int get() = nav?.maneuver ?: DashCommands.NAV_MANEUVER_CONTINUE

    /** No guidance any more: the nav packets stop and the route card goes back to the card with no route. */
    fun clearNavInfo() {
        nav = null
        forgetLastManeuver()
    }

    /** A guidance's last maneuver never carries over to the next. */
    private fun forgetLastManeuver() {
        lastVisibleManeuver = null
        keepingManeuver = false
    }

    /**
     * Whether the music card is sent at all: only while the joystick page has a music control in the media mode
     * ([com.reau.app.aa.JoystickBindings.mediaControls]). Turned off while the card is up, it is cleared at the next second.
     */
    @Volatile var musicCardAllowed: Boolean = true

    /**
     * Whether the dash is told its zoom state (06 0C), which is what makes its own "ZOOM" with the "-" and "+" show in the map mode.
     * Off, it is never sent: the rider has no zoom on the joystick page, so the dash has no zoom to name. Turned on while streaming
     * it goes out at once.
     */
    var zoomLabel: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            val said = if (value) "on" else "off: the joystick page lacks a zoom control"
            com.reau.app.data.RideDiagnostics.log("dash", "zoom label $said")
            val sock = socket
            if (value && _state.value == DashState.STREAMING && sock != null) {
                scope.launch(Dispatchers.IO) { runCatching { sock.send(DashCommands.zoomIcons()) } }
            }
        }

    // The dash's own music and call cards, at 1 Hz while streaming.
    @Volatile private var nowPlayingPackets: List<ByteArray>? = null


    /** The music card is up on the dash (a test sets it: the cards go through a socket). */
    @Volatile internal var musicShown = false
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
        val ringBefore = ringOn
        callerName = name
        callerInCall = inCall
        ringAfterCall(ringBefore)
        sendCardsNow()
    }

    /** The call is over; the card is cleared at once. */
    fun clearCaller() {
        if (callerName == null) return
        val ringBefore = ringOn
        callerName = null
        callerInCall = false
        ringAfterCall(ringBefore)
        sendCardsNow()
    }

    /** The call came or went and the ring changed with it (it was hidden by the switch): the route card goes at once, not at the next second. */
    private fun ringAfterCall(before: Boolean): Boolean {
        if (ringOn == before) return false
        com.reau.app.data.RideDiagnostics.log("dash", "ring ${if (ringOn) "shown" else "hidden again"} for the call")
        socket?.let { sock -> runCatching { sock.send(liveRouteCard(projectionOn = true)) } }
        return true
    }

    private fun sendCardsNow() {
        if (_state.value == DashState.STREAMING) scope.launch(Dispatchers.IO) { sendCards() }
    }

    /**
     * One round of the cards: the call card (or its clear, once) and the music card. The album's picture goes after, out of the
     * monitor: its slices take up to 0.66 s and a call card must not wait for them (A-14).
     */
    private fun sendCards() {
        val sock = socket ?: return
        val cover = synchronized(this) { sendCardsHeld(sock) }
        cover?.let { sendCover(sock, it) }
    }

    /** [sendCards] under the monitor; the album's slices still to send, if any. */
    private fun sendCardsHeld(sock: DashSocket): List<ByteArray>? {
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
        val music = nowPlayingPackets.takeIf { musicCardAllowed }
        if (music != null) {
            music.forEach { runCatching { sock.send(it) } }
            if (!musicShown) com.reau.app.data.RideDiagnostics.log("dash", "music card shown")
            musicShown = true
            return coverDue()
        } else if (musicShown) {
            runCatching { sock.send(DashCommands.musicActive(false)) }
            com.reau.app.data.RideDiagnostics.log("dash", "music card cleared")
            musicShown = false
        }
        return null
    }

    /** The album's picture, once: its slices, taken by the one round that sends them; the card's flag said it comes. */
    private fun coverDue(): List<ByteArray>? {
        val slices = coverSlices ?: return null
        if (!coverPending) return null
        coverPending = false
        return slices
    }

    /** The album's slices with a pause between them. */
    private fun sendCover(sock: DashSocket, slices: List<ByteArray>) {
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
    private fun liveRouteCard(projectionOn: Boolean): ByteArray {
        val n = shownNav()
        return if (n != null) DashCommands.routeCard(
            // The street while guiding, else REau.
            n.road.ifBlank { destinationName }, projectionOn,
            maneuver = ringGlyph(n),
            primaryUnit = n.primaryUnit,
            totalDist = n.totalDist,
            totalUnit = n.totalUnit,
            etaHHMM = n.eta,
            decimalComma = decimalComma(),
            remainingSeconds = n.remaining,
            nextManeuver = nextShown(n),
            proximity = proximityBar(n),
        )
        else DashCommands.idleRouteCard(if (ringOn) idleCard else DashIdleCard.HIDDEN, destinationName, projectionOn, clockHHMM())
    }

    /** The phone's own decimal separator is a comma (pt-BR): the dash writes its distances so (05 0A). */
    private fun decimalComma(): Boolean = java.text.DecimalFormatSymbols.getInstance().decimalSeparator == ','

    /** The route card as it goes to the dash now (internal, for the tests). */
    internal fun routeCardNow(): ByteArray = liveRouteCard(projectionOn = true)

    /**
     * The maneuver after the next (05 03): shown only from 100 m of the turn (the proximity bar below 5), 0xFF otherwise.
     */
    private fun nextShown(n: NavFigures): Int = when {
        proximityBar(n) >= FAR_BAR -> DashCommands.NEXT_MANEUVER_NONE
        else -> n.next
    }

    /** The proximity bar (05 0C) for the distance to the turn, in metres whatever the unit the dash shows. */
    private fun proximityBar(n: NavFigures): Int {
        val metres = DashCommands.navMeters(n.primaryDist, n.primaryUnit)
        val keep = n.maneuver == com.reau.app.dash.nav.DashManeuverGlyphs.FORK_LEFT || n.maneuver == com.reau.app.dash.nav.DashManeuverGlyphs.FORK_RIGHT
        return DashCommands.proximityLevel(metres, keep)
    }

    /** The ring's glyph while guiding: the maneuver itself (hiding it never worked). A debug build's arrow test sends its own byte (see [shownNav]). */
    private fun ringGlyph(n: NavFigures): Int = com.reau.app.debug.ArrowTrial.byte() ?: bubbleManeuver(ringOn, n.maneuver)

    /**
     * The figures the cards and the nav packet are made from: the guidance's, or, while a debug build's arrow test is on
     * ([com.reau.app.debug.ArrowTrial], which a release does not have: it answers none there), a card with that arrow alone and no distance, even with no
     * guidance. Nothing else reads this: the ring's own logic goes on with [nav].
     */
    private fun shownNav(): NavFigures? = com.reau.app.debug.ArrowTrial.byte()?.let { trial ->
        nav ?: NavFigures(
            trial, 0, DashCommands.NAV_UNIT_METERS, 0, DashCommands.NAV_UNIT_METERS, null, "", DashCommands.REMAINING_OMIT, DashCommands.NEXT_MANEUVER_NONE,
        )
    } ?: nav

    /** The phone's clock as 4 digits, the ETA of the idle card's arrival pin. */
    private fun clockHHMM(): String = java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String, network: android.net.Network? = null) {
        // Flip to CONNECTING synchronously, before launching — otherwise two near-simultaneous
        // callers (the WiFi-reconnect and session-retry paths of the caller both call connect()
        // after an error) can both pass this guard while _state is still IDLE/ERROR, each starting
        // its own runSession() and binding the same fixed UDP ports; the second one's fail() then
        // closes the `socket` field out from under the first, working session.
        // A check and then a write was not one step: two callers could both see IDLE. Now the flip is the check.
        if (!claimConnecting()) return
        disconnecting = false
        // Whatever an earlier session still runs (its session coroutine waiting for the dash's answer, its
        // stats line, its cards) is ended before this one starts, and this one gets a number of its own.
        sessionJob?.cancel(); statsJob?.cancel(); cardsJob?.cancel()
        val mine = ++sessionGeneration
        Log.i(TAG, connectLine(ssid, network))
        com.reau.app.data.RideDiagnostics.log("session", connectLine(ssid, network))
        sessionJob = scope.launch(Dispatchers.IO) { runSession(mine, ssid, network) }
    }

    /** IDLE or ERROR to CONNECTING in one atomic step; false when the session is anything else (or another caller got there first). */
    private fun claimConnecting(): Boolean {
        while (true) {
            val seen = _state.value
            if (seen != DashState.IDLE && seen != DashState.ERROR) return false
            if (_state.compareAndSet(seen, DashState.CONNECTING)) return true
        }
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
        streamingStartMs = MonotonicClock.nowMs()
        // The dash's 10 s mark came before the stream: it was alive then, so the stream starts at the pace of one that is (no wait for
        // a mark that does not come again, 12:00:53 to 12:01:08 at 2 fps on 20/10/2026).
        if (clockNoticeBeforeStream) aliveWithoutAck = true
        coverPending = coverSlices != null // a stream is new to the dash: the picture again
        com.reau.app.data.RideDiagnostics.log("session", "→ STREAMING")
        ImageGap.clock.streaming(MonotonicClock.nowMs())?.let { com.reau.app.data.RideDiagnostics.log("session", it) }
        // Projection-on route card + faster keep-alive begin now
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchCards()
    }

    fun sendRtp(packet: ByteArray) { socket?.sendRtp(packet) }

    /** A keyframe went out in [packets] RTP packets: kept for the line written when the dash goes quiet ([SendTail]). */
    fun keyframeSent(bytes: Int, packets: Int) = stats.sendTail.keyframe(bytes, packets)

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
        nav = null
        forgetLastManeuver()
        socket?.let { sock ->
            val exit = ExitSequence(MonotonicClock::nowMs).also { exitSequence = it }
            // Each command is told after it: whether it reached the wire and when (ExitSequence), with the timing unchanged.
            fun send(name: String, packet: ByteArray) = exit.step(name, runCatching { sock.send(packet) }.getOrDefault(false))
            val goodbye = Runnable {
                send("projection-stop", DashCommands.projectionStop())
                send("projection-off", DashCommands.projectionOff())
                // Extended exit sequence documented by another open implementation (see
                // DashCommands.exitBack's doc) — best-effort, additive, unverified on real hardware.
                // Meant to give the dash a cleaner exit-to-menu instead of possibly staying "hung"
                // until a power-cycle; each send is independently guarded so one failing never blocks
                // the rest of teardown.
                send("back", DashCommands.exitBack())
                repeat(4) { send("idle", DashCommands.exitProjectionIdle()) }
                repeat(2) { send("boundary", DashCommands.exitSessionBoundary()) }
                sock.close()
                com.reau.app.data.RideDiagnostics.log("session", exit.summary())
            }
            // A guard: today every caller is on the service's worker or the IO scope, but the rider's "disconnect" once called this
            // on the main thread, where Android refuses network: every send above failed with NetworkOnMainThreadException and the
            // dash never heard the session was over (observed in ride logs). On the main thread the goodbye goes out on a thread of
            // its own, waited for briefly so the socket is closed before a next connection opens its own.
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
            // The network that refused the socket is told first, so it is dropped before the projection tries the dash again.
            if (NetworkBlock.isBindRefusal(e) && isCurrent(mine)) onBindResult?.invoke(network, true)
            fail(NetworkBlock.describe(e), mine) // an EPERM says the network was blocked
        }
    }

    /**
     * [socketFactory]'s socket. With the experimental switch on ([bindRetry]), a bind to [network] that Android refuses (EPERM) is tried again on
     * the same network, [BindRetry.MAX_RETRIES] times with [BindRetry.PAUSE_MS] between, before the refusal is let through to the caller (and so to
     * [onBindResult], which lets the network go). The wait is the coroutine's own (it holds no thread, and the session runs on the IO threads, never
     * the main one); a session that was replaced meanwhile stops waiting. Each try builds the sockets from the start: a refused one closed what it had opened.
     */
    @Suppress("TooGenericExceptionCaught") // only a refused bind is retried; anything else is thrown again as it was
    private suspend fun socketWithBindRetries(mine: Int, network: android.net.Network?): DashSocket {
        var retries = 0
        while (true) {
            try {
                return socketFactory(network).also {
                    if (retries > 0) com.reau.app.data.RideDiagnostics.log("wifi", BindRetry.workedLine(network, retries))
                }
            } catch (e: Exception) {
                val refusal = NetworkBlock.isBindRefusal(e)
                if (!BindRetry.again(bindRetry(), refusal, retries) || !isCurrent(mine)) throw e
                retries++
                com.reau.app.data.RideDiagnostics.warn("wifi", BindRetry.retryLine(network, retries, timing.bindRetryPauseMs))
                delay(timing.bindRetryPauseMs)
            }
        }
    }

    /**
     * The session's sockets, with the receive loop, the status heartbeat and the stats line started on them; null
     * after a failure already said through [fail] (the ports in use), or for a session connect() has replaced
     * meanwhile, whose sockets are closed again at once.
     */
    private suspend fun openSockets(mine: Int, ssid: String, network: android.net.Network?): DashSocket? {
        val sock = try {
            socketWithBindRetries(mine, network)
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
                stats.trail.clear()
                stats.sendTail.clear()
                socketsOpenedMs = MonotonicClock.nowMs()
                DashHeard.trail = stats.trail
                DashHeard.tail = stats.sendTail
                onBindResult?.invoke(network, false)
                linkNetwork = network
                linkHistory.clear()
                linkBaselineLogged = false
                launchStatsLine()
                auth = DashAuth(ssid)
                rxFailures.ok()
                authConfirmed = false
                keyExchangeRefusals.clear()
                authRetries = 0
                beginAuthState(network)
                loggedFirstAck = false
                aliveWithoutAck = false
                streamingStartMs = 0L
                lastRxMs = 0L
                rxPackets = 0
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
        sendInitialBurst(sock)
        com.reau.app.data.RideDiagnostics.log(
            "auth", "initial burst sent — waiting for 07 01 01 (${AUTH_TIMEOUT}ms, up to ${DashReadiness.CAP_MS}ms for a dash that is only starting)",
        )

        Log.i(TAG, "Waiting for auth (07 01 01): ${AUTH_TIMEOUT}ms, up to ${DashReadiness.CAP_MS}ms for a dash that is only starting…")
        var lastBurstMs = MonotonicClock.nowMs()
        burstStartMs = lastBurstMs
        var resends = 0
        val greeting = DashReadiness.Greeting(timing.keyPatienceMs, MAX_BURST_RESENDS)
        while (!authConfirmed && MonotonicClock.nowMs() < authDeadline(burstStartMs)) {
            delay(100)
            val now = MonotonicClock.nowMs()
            // The dash's heartbeat counter says how long it has been on: until it is ready the greeting goes every 2 s, and again when
            // it becomes ready ([DashReadiness.Greeting]).
            val first = firstHeartbeat.takeIf { it >= 0 }
            val notReady = greeting.notReady(first, firstHeartbeatAtMs, now)
            // Seen at 20:24:02 on 19/10/2026 and 12:00:34 on 20/10/2026: the dash answered with its telemetry but never sent its key,
            // and the key came only with a new attempt (50 ms, 17 s after the first burst). So the burst is sent again every 3 s up to the
            // deadline, and at once when the dash's own 10 s mark comes with no key (the dash has just turned its clock).
            // The request of the 10 s mark is taken (cleared) here; with the key offered meanwhile there is nothing to ask for.
            val onNotice = resendOnNotice.also { if (it) resendOnNotice = false } && auth?.keySeen != true
            val due = greeting.due(first, firstHeartbeatAtMs, now, now - lastBurstMs, auth?.keySeen == true)
            if (!authConfirmed && (onNotice || due)) {
                resends++
                lastBurstMs = now
                com.reau.app.data.RideDiagnostics.log(
                    "auth", if (onNotice) {
                        "the dash's 10 s mark came with no RSA pubkey — burst sent again now ($resends)"
                    } else if (greeting.readyMoment) {
                        "the dash is ready now (heartbeat ${DashReadiness.READY_COUNT}): burst sent again ($resends)"
                    } else if (notReady) {
                        val age = DashReadiness.counterAt(checkNotNull(first), firstHeartbeatAtMs, now)
                        "the dash is only $age s old (ready from ${DashReadiness.READY_COUNT}): burst sent again ($resends)"
                    } else {
                        "no RSA pubkey ${timing.keyPatienceMs}ms after the last burst — burst sent again (${greeting.patientSent}/$MAX_BURST_RESENDS)"
                    },
                )
                sendInitialBurst(sock)
            }
        }

        if (!authConfirmed) {
            fail(
                authTimeoutReason(
                    heardFromDash = lastRxMs > 0L, ssid = ssid, keySeen = auth?.keySeen ?: true,
                    resends = resends, noticeAfterMs = clockNoticeAfterMs,
                    rssiDbm = linkProbe(linkNetwork)?.rssiDbm, packets = rxPackets,
                    waitedMs = MonotonicClock.nowMs() - burstStartMs,
                ),
                mine,
            )
        } else {
            Log.i(TAG, "Authenticated ✓")
            com.reau.app.data.RideDiagnostics.log("auth", DashReadiness.keyLine(MonotonicClock.nowMs() - burstStartMs, resends))
            com.reau.app.data.RideDiagnostics.log("auth", "authenticated (07 01 01) — entering nav mode")
        }
        return authConfirmed
    }

    /**
     * The state of a session's authentication, reset as it opens on [network]. The dash's 10 s mark belongs to the Wi-Fi link, not to the
     * session: after a greeting the dash ignored, the session made again on the same link does not get it again (3 of 3 in the logs of
     * 20/10/2026, which then streamed 15 s at 2 fps), so a mark seen on this link counts as seen for this session too.
     */
    internal fun beginAuthState(network: android.net.Network?) {
        clockNoticeAfterMs = -1L
        firstHeartbeat = -1
        clockNoticeBeforeStream = linkNotice.knownOn(network ?: NO_NETWORK)
        resendOnNotice = false
        resentOnNotice = false
    }

    /**
     * The dash's own 10 s mark (09 06 55) came in [state]; true for the first of the session. Its time is kept (counted from the
     * first burst) for the log and the failure message; before the stream (authenticating or ready) it also makes startStreaming()
     * count the dash as alive, and while authenticating with no key offered the burst is asked to go out again at once (once a
     * session; the authentication loop sends it).
     */
    private fun noteClockNotice(state: DashState): Boolean {
        linkNotice.seenOn(linkNetwork ?: NO_NETWORK)
        val first = clockNoticeAfterMs < 0
        if (first) clockNoticeAfterMs = (MonotonicClock.nowMs() - burstStartMs).coerceAtLeast(0)
        if (state == DashState.AUTHENTICATING || state == DashState.READY) clockNoticeBeforeStream = true
        if (resendOnClockNotice(state, auth?.keySeen == true, resentOnNotice)) {
            resentOnNotice = true
            resendOnNotice = true
        }
        return first
    }

    /** The dash's first heartbeat of the session: its counter (how long the dash has been on), kept for [DashReadiness]. */
    private fun noteHeartbeat(value: ByteArray) {
        val counter = DashReadiness.counterOf(value) ?: return
        firstHeartbeatAtMs = MonotonicClock.nowMs()
        firstHeartbeat = counter
        ImageGap.clock.dashAge(firstHeartbeatAtMs, counter)
        com.reau.app.data.RideDiagnostics.log("auth", DashReadiness.ageLine(counter))
    }

    /** When the wait for the key ends: [AUTH_TIMEOUT] from the entry, stretched by a dash that is not ready yet ([DashReadiness.deadlineMs]). */
    private fun authDeadline(burstStart: Long): Long =
        DashReadiness.deadlineMs(burstStart, firstHeartbeat.takeIf { it >= 0 }, firstHeartbeatAtMs)

    private suspend fun sendInitialBurst(sock: DashSocket) {
        for (pkt in DashCommands.initialBurst(HOSTNAME, status = phoneStatusProvider())) {
            sock.send(pkt)
            delay(BURST_PAUSE)
        }
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
        if (zoomLabel) sock.send(DashCommands.zoomIcons()) // the "-" and "+" by the dash's ZOOM, at a route's start
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
                receivePacket(pkt, sock, mine)
            }
        }
    }

    /** A packet that reached the receive loop during our own disconnect: the dash answering the exit commands (only its TLV keys are written). */
    private fun noteExitAnswer(pkt: ByteArray) {
        val sequence = exitSequence ?: return
        val keys = runCatching { K1GPacket.parseIncoming(pkt).joinToString(" ") { "%02X%02X".format(it.type, it.sub) } }.getOrDefault("?")
        sequence.reply(keys)?.let { com.reau.app.data.RideDiagnostics.log("session", it) }
    }

    // Packets in a row whose handling threw; a session starts it over.
    private val rxFailures = FailureStreak(RX_EXCEPTIONS_BEFORE_FAIL)

    /**
     * One packet from the dash, handled. An exception from handling it costs that packet and one line in the log (the first of a
     * run, never the bytes): it used to escape the receive coroutine, which had no handler, and take the app down (audit of
     * 20/10/2026). When every packet throws, [RX_EXCEPTIONS_BEFORE_FAIL] in a row, the session fails with a clear reason so that
     * the projection's own retries take over, instead of listening to a dash it cannot make sense of.
     */
    @Suppress("TooGenericExceptionCaught") // whatever a packet makes the handler throw is contained here
    internal fun receivePacket(pkt: ByteArray, sock: DashSocket, mine: Int = sessionGeneration) {
        lastRxMs = MonotonicClock.nowMs()
        if (disconnecting) noteExitAnswer(pkt)
        rxPackets++
        DashHeard.heard(lastRxMs)
        try {
            dispatchIncoming(pkt, sock)
            rxFailures.ok()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val exhausted = rxFailures.failed()
            if (rxFailures.count == 1) {
                com.reau.app.data.RideDiagnostics.warn("dash", "a packet from the dash could not be handled (${e.javaClass.simpleName}); it is dropped")
            }
            if (exhausted) fail("Dash packets keep failing to be handled ($RX_EXCEPTIONS_BEFORE_FAIL in a row, ${e.javaClass.simpleName})", mine)
        }
    }

    /**
     * Whether a key-exchange TLV is taken: no rejection (07 01 not 01) once the dash has confirmed the key, which would start the exchange
     * over and send a new session key to whoever answers first (26/10/2026 review). From any address, as the telemetry (the owner's
     * decision: not every dash is known). Said once per session.
     */
    internal fun keyExchangeAccepted(tlv: com.reau.app.dash.protocol.Tlv): Boolean {
        val lateRejection = authConfirmed && tlv.sub == 0x01 && tlv.value.firstOrNull() != 0x01.toByte()
        if (!lateRejection) return true
        val why = "a rejection after the dash confirmed the key"
        if (keyExchangeRefusals.add(why)) com.reau.app.data.RideDiagnostics.warn("auth", "key exchange packet ignored: $why")
        return false
    }

    // What [keyExchangeAccepted] already said in this session.
    private val keyExchangeRefusals = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** For the tests that drive [dispatchIncoming] through the session's states without a handshake. */
    internal fun stateForTest(state: DashState) {
        _state.value = state
    }

    // Internal (not private) so DashSessionTelemetryTest can feed it a hand-built raw TLV packet
    // directly, without needing a real handshake/dash peer.
    @Suppress("TooGenericExceptionCaught") // the button handler must never end the receive loop
    internal fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val parsed = K1GPacket.parse(pkt)
        val tlvs = parsed.tlvs
        if (parsed.cutShort) sock.stats?.malformed()
        // Read once, counted from the same list (the socket's stats read the packet a second time).
        sock.stats?.received(tlvs, MonotonicClock.nowMs())
        // No packet bytes to logcat (the detailed log's DashDump shows them, behind its switch).
        for (tlv in tlvs) {
            if (firstHeartbeat < 0 && tlv.type == 0x01 && tlv.sub == 0x01) noteHeartbeat(tlv.value)
            // The screen "Dados da moto" keeps what the dash says, but not the key exchange (07) nor the buttons (09 00).
            val kept = tlv.type != 0x07 && tlv.type != 0x0F && !(tlv.type == 0x09 && tlv.sub == 0x00)
            if (kept) readings?.onValue(tlv.type, tlv.sub, tlv.value)
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                if (!keyExchangeAccepted(tlv)) continue
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
                if (noteClockNotice(_state.value)) {
                    com.reau.app.data.RideDiagnostics.log("dash", clockNoticeLine(clockNoticeAfterMs))
                }
                if (!loggedFirstAck && confirmsFirstFrame(_state.value)) loggedFirstAck = true
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
                val readAtNs = sock.arrivedNs.takeIf { it != 0L } ?: System.nanoTime()
                // The echo, the zoom icons after a zoom and, when the press changes the ring, the card with it: all on the wire before anything
                // is logged (the log's lock is shared with the video and Android Auto threads), so the ring changes as soon as it can.
                val answers = repliesToButton(btn, zoomLabel)
                val ringChanged = ringAfterPress(btn.toInt() and 0xFF)
                sock.sendBatch(if (ringChanged) answers + liveRouteCard(projectionOn = true) else answers)
                if (ringChanged) logRingByPress(btn.toInt() and 0xFF, (System.nanoTime() - readAtNs) / NS_PER_MS)
                infoSwitchLine?.let { line ->
                    infoSwitchLine = null
                    com.reau.app.data.RideDiagnostics.log("dash", line)
                }
                Dbg.i(TAG) { "JOYSTICK 09 00 → code 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}" }
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
        val into = if (streamingStartMs > 0L) {
            "%.1f s into the stream".format(java.util.Locale.US, (MonotonicClock.nowMs() - streamingStartMs) / 1000.0)
        } else {
            "not streaming"
        }
        com.reau.app.data.RideDiagnostics.log("dash", "notice $name #$n, $into, ${_state.value}: $did")
    }

    private fun launchStatusHeartbeat(sock: DashSocket, mine: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            val sentSinceRx = HeartbeatsSinceRx()
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
                // RX watchdog: once the dash has spoken at all (rxSilenceFails), a long silence means it
                // dropped (out of range / off). UDP gives no error, so detect it here and fail the
                // session instead of streaming into the void while the UI still says "connected".
                if (rxSilenceFails(_state.value, lastRxMs, MonotonicClock.nowMs(), timing.rxWatchdogMs, sentSinceRx.tick(lastRxMs))) {
                    val silent = (MonotonicClock.nowMs() - lastRxMs) / 1000
                    Log.w(TAG, "RX watchdog: no dash packets for ${silent}s — link lost")
                    com.reau.app.data.RideDiagnostics.error("error", "RX watchdog: dash silent ${silent}s → link lost")
                    logLinkAtFailure()
                    com.reau.app.data.ProjectionSummary.current.watchdog("dash-rx")
                    fail("Dash stopped responding — connection lost", mine)
                    break
                }
                // First-ack watchdog: a real gap the RX watchdog above does not catch, because it
                // needs the dash to have spoken at least once — it detects a link that WORKED and
                // then died, not one that never worked in the first place. Confirmed
                // live in a ride log: the dash WiFi cycled through
                // REQUESTING → unavailable → retry for over 6 MINUTES straight (never once reaching
                // CONNECTED again after an initial blip), while this session's socket — still bound
                // to the stale Network from before the blip — kept accepting sendto() calls without
                // throwing (no exception ⇒ no fail() from the RX loop's own catch block either).
                // _state sat in STREAMING the entire time with zero feedback to the rider: the phone
                // showed "transmitting" while the physical dash showed nothing but its own idle
                // compass-ring screen. See firstAckTimedOut's own doc for the pure check.
                val firstAck = firstAckVerdict(
                    loggedFirstAck || aliveWithoutAck, streamingStartMs, MonotonicClock.nowMs(), FIRST_ACK_TIMEOUT_MS,
                    lastRxMs, timing.rxWatchdogMs,
                )
                if (firstAck == FirstAck.ALIVE_WITHOUT_ACK) {
                    aliveWithoutAck = true
                    com.reau.app.data.RideDiagnostics.warn(
                        "dash",
                        "no 09 06 55 after ${FIRST_ACK_TIMEOUT_MS / 1000}s, but the dash answers: the stream goes on (the RX watchdog watches it)",
                    )
                }
                if (firstAck == FirstAck.DEAD) {
                    val waited = (MonotonicClock.nowMs() - streamingStartMs) / 1000
                    Log.w(TAG, "First-ack watchdog: dash never confirmed decoding a frame after ${waited}s — link likely dead")
                    com.reau.app.data.RideDiagnostics.error("error", "first-ack watchdog: no decode ack after ${waited}s → link likely dead")
                    logLinkAtFailure()
                    com.reau.app.data.ProjectionSummary.current.watchdog("dash-first-ack")
                    fail("Dash never showed the video — check the WiFi link", mine)
                    break
                }
                delay(timing.watchdogTickMs)
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
        logTrail("a watchdog fired")
    }

    /** The black box ([PacketTrail]) in the ride log, once for a failure (a watchdog writes it, then [fail] would again). */
    private fun logTrail(why: String) {
        val now = MonotonicClock.nowMs()
        if (now - trailLoggedMs < TRAIL_ONCE_MS) return
        trailLoggedMs = now
        stats.trail.report(why, now).forEach { com.reau.app.data.RideDiagnostics.log("link", it) }
        val open = socketsOpenedMs.takeIf { it > 0L }?.let { "session open for ${(now - it) / MS_PER_SECOND} s" }
        stats.sendTail.reportOnce(why, open)?.let { com.reau.app.data.RideDiagnostics.log("link", it) }
    }

    /** One dash-stats line every [statsEveryMs] while the session lives. */
    private fun launchStatsLine() {
        statsJob?.cancel()
        statsWindowStartMs = MonotonicClock.nowMs()
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(statsEveryMs)
                logStats(end = false)
            }
        }
    }

    private fun logStats(end: Boolean) {
        val now = MonotonicClock.nowMs()
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
                navPacketNow()?.let { socket?.send(it) }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    /** The nav packet (05 02: the maneuver's bubble, the distances) as it goes to the dash now; null with no guidance (and no arrow test). */
    internal fun navPacketNow(): ByteArray? = shownNav()?.let { n ->
        DashCommands.activeNavPacket(
            maneuver = ringGlyph(n),
            primaryDist = n.primaryDist,
            primaryUnit = n.primaryUnit,
            totalDist = n.totalDist,
            totalUnit = n.totalUnit,
            road = n.road,
            decimalComma = decimalComma(),
            proximity = proximityBar(n),
            nextManeuver = nextShown(n),
        )
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
        ImageGap.clock.lost(MonotonicClock.nowMs(), DashHeard.agoMs(MonotonicClock.nowMs()))
        logTrail("the session failed")
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
         * The line for the dash's first 09 06 55 of a session, [afterMs] after the first burst. It is the dash's own 10 s clock
         * (it came at +10 s with no video at all in the logs of 20/10/2026), not proof of a decoded frame: the log never says "video accepted".
         */
        internal fun clockNoticeLine(afterMs: Long): String =
            "dash notice 09 06 55 at +%.1f s of the burst (the dash's own 10 s mark; not proof of a decoded frame)"
                .format(java.util.Locale.US, afterMs / 1_000.0)

        /**
         * The RX watchdog: while streaming, a dash that has spoken ([lastRxMs] > 0) and then stays silent past [limitMs] is lost.
         * It used to wait for the 10 s mark or 15 s (the first-ack watchdog) to be armed, so a dash that fell silent right after
         * the stream began took 15 s to be noticed. A dash that never spoke is the first-ack watchdog's.
         */
        internal fun rxSilenceFails(
            state: DashState,
            lastRxMs: Long,
            nowMs: Long,
            limitMs: Long,
            heartbeatsSinceRx: Int = MIN_HEARTBEATS_BEFORE_SILENT,
        ): Boolean =
            state == DashState.STREAMING && lastRxMs > 0L && nowMs - lastRxMs > limitMs && heartbeatsSinceRx >= MIN_HEARTBEATS_BEFORE_SILENT

        /** The dash counts as silent only after this many heartbeats sent since its last packet (see [HeartbeatsSinceRx]). */
        internal const val MIN_HEARTBEATS_BEFORE_SILENT = 4

        /**
         * Why the authentication ran out. Only a dash that answered and still did not accept points at the SSID (the
         * dash checks it inside the encrypted handshake); not a single packet means the dash is not there (seen with
         * the bike's Wi-Fi already gone, when the log used to send the rider to check the SSID).
         */
        /** The log's line of a [connect]: the dash's name masked (it ties a log to one bike). */
        internal fun connectLine(ssid: String, network: Any?): String =
            "connect() — ssid='${DashWifiManager.maskSsid(ssid)}' network=$network"

        /** The patient pace of the greeting once the dash is ready, which [DashReadiness.Greeting] applies in the loop; kept as its own rule for the tests. */
        internal fun burstDue(sinceLastBurstMs: Long, keySeen: Boolean, resends: Int): Boolean =
            !keySeen && resends < MAX_BURST_RESENDS && sinceLastBurstMs >= KEY_PATIENCE_MS

        /**
         * Whether the dash's 10 s mark (09 06 55) in [state] makes the burst go out again at once: while authenticating, with no
         * key offered ([keySeen]) and not done already this session ([alreadyDone]).
         */
        internal fun resendOnClockNotice(state: DashState, keySeen: Boolean, alreadyDone: Boolean): Boolean =
            state == DashState.AUTHENTICATING && !keySeen && !alreadyDone

        /**
         * The dash only takes the stream with its screen set to Digital (found by better-dash and kept by EstrelaAustral); on another
         * setting it answers and the authentication never closes. Said when the dash answered, not when it stayed silent (then it is off).
         */
        internal const val DIGITAL_HINT = "If it keeps happening, check that the dash is set to Appearance > Screen type > Digital."

        /**
         * The link was weak while the key exchange ran out: the signal at [WEAK_SIGNAL_DBM] or under, or fewer than [FEW_PACKETS] packets in the
         * wait ([packets]; a dash within reach sends its heartbeat every second and its telemetry besides, 14 or more in 5 s in the logs).
         */
        internal fun weakLink(rssiDbm: Int?, packets: Int): Boolean =
            (rssiDbm != null && rssiDbm <= WEAK_SIGNAL_DBM) || packets < FEW_PACKETS

        /**
         * Why the wait for the dash's key (07 01 01) ran out. A dash that answered and still did not accept points at the SSID or the screen type
         * only on a link that was good: ride log of 05/11/2026 07:13:21, -80 dBm and 3 packets in 15 s, told the rider to check the name of a dash that
         * was on and right ([rssiDbm], [packets] and [waitedMs] say how weak the link was; the defaults say nothing, as before).
         */
        @Suppress("LongParameterList") // the facts of one verdict
        internal fun authTimeoutReason(
            heardFromDash: Boolean,
            ssid: String,
            keySeen: Boolean = true,
            resends: Int = MAX_BURST_RESENDS,
            noticeAfterMs: Long = -1L,
            rssiDbm: Int? = null,
            packets: Int = Int.MAX_VALUE,
            waitedMs: Long = AUTH_TIMEOUT,
        ): String = when {
            heardFromDash && weakLink(rssiDbm, packets) ->
                "Auth timed out — the dash answered but the link is weak (" +
                    listOfNotNull(
                        rssiDbm?.let { "$it dBm" },
                        if (packets < FEW_PACKETS) "$packets packets in ${waitedMs / 1_000} s" else null,
                    ).joinToString(", ") +
                    "): the dash is probably too far from the phone or something is in between, not a wrong name. Move the phone closer to the dash."
            heardFromDash && !keySeen ->
                "Auth timed out — the dash answered but never sent its key (07 00 / 07 03), " +
                    "even after the burst was sent again $resends times" +
                    (if (noticeAfterMs >= 0) "; its own 10 s mark (09 06 55) came ${noticeAfterMs / 1_000} s after the first burst" else "") +
                    ". $DIGITAL_HINT"
            heardFromDash ->
                "Auth timed out — the dash answered but never accepted (no 07 01 01). " +
                    "Check SSID matches '${DashWifiManager.maskSsid(ssid)}'. $DIGITAL_HINT"
            else -> "Auth timed out — not a single packet from the dash (bike off, or its Wi-Fi gone?)."
        }

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
         * What a joystick press gets back: its echo, and after a zoom or recentre the zoom state with both icons (with the [zoomLabel] on).
         */
        internal fun repliesToButton(code: Byte, zoomLabel: Boolean = true): List<ByteArray> =
            if (zoomLabel && (code.toInt() and 0xFF) in ZOOM_CODES) listOf(DashCommands.buttonAck(code), DashCommands.zoomIcons())
            else listOf(DashCommands.buttonAck(code))

        /** The dash's zoom (0x13, 0x14) and recentre (0x15) codes. */
        private val ZOOM_CODES = setOf(0x13, 0x14, 0x15)

        /** The proximity bar from which the turn is more than 100 m away (5 and 6). */
        private const val FAR_BAR = 5
        private const val NS_PER_MS = 1_000_000L

        private const val TAG           = "DashSession"

        /** The link of a session without a Wi-Fi network of its own (a test's, or a socket not bound to a network). */
        private val NO_NETWORK = Any()
        private const val AUTH_TIMEOUT  = 15_000L

        /** A link at this signal or weaker is a dash too far away ([weakLink]). */
        internal const val WEAK_SIGNAL_DBM = -75

        /** Fewer packets than this from the dash in the whole wait for the key is a link too weak to carry the exchange ([weakLink]). */
        internal const val FEW_PACKETS = 10
        /** Packets in a row whose handling threw before the session is failed (each one is dropped and the loop goes on). */
        internal const val RX_EXCEPTIONS_BEFORE_FAIL = 20
        /** How long the dash may take to offer its key after a burst before the burst is sent again, and how many times. */
        internal const val KEY_PATIENCE_MS = 3_000L
        internal const val MAX_BURST_RESENDS = (AUTH_TIMEOUT / KEY_PATIENCE_MS).toInt() - 1
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
        internal const val RX_WATCHDOG_MS = 4_000L
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val MS_PER_SECOND = 1_000L
        /** A watchdog's failure and the [fail] that follows it are one event: the packet trail is written once for both. */
        private const val TRAIL_ONCE_MS = 2_000L
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
        internal const val FIRST_ACK_TIMEOUT_MS = 15_000L
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
