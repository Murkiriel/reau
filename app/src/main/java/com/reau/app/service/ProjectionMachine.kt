package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.dash.DashState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map

/** The dash side as the machine sees it. */
enum class DashLink { OFF, CONNECTING, READY, DROPPED, TAKEN, NAME_NEEDED }

/** Off, getting the service ready (foreground, locks), or running with both sides started. */
enum class Phase { STOPPED, PREPARING, RUNNING }

/** The one thing to tell the rider about the projection, for the notification and the screen. */
enum class Headline {
    STOPPED,

    /** The rider switched it off and the stop is still being carried out (it may take seconds): not yet "stopped". */
    STOPPING,
    PROBLEM,
    PREPARING,
    CONNECTING,
    ANDROID_AUTO_SERVER_OFF,
    ANDROID_AUTO_UNRESPONSIVE,

    /** Android Auto does not answer right after REau's own stop: it is still closing the previous session (up to ~1 min). */
    ANDROID_AUTO_CLOSING_PREVIOUS,

    /** Android Auto 17.3 or older was asked to call REau and never did. */
    ANDROID_AUTO_NOT_CALLING,

    /** Android Auto's session is open and sends no picture: the dash shows black. */
    ANDROID_AUTO_NO_VIDEO,

    /** Streaming, but Android Auto sends no new picture while the bike moves: the dash repeats the last one, which looks alive. */
    ANDROID_AUTO_IMAGE_STALE,
    DASH_LOST,

    /** The dash has not been ready once in this session, and its Wi-Fi is still being tried: nothing was lost yet. */
    DASH_SEARCHING,

    /**
     * As [DASH_SEARCHING], but the dash's Wi-Fi was never joined from this app: Android is about to ask the rider, in its own
     * dialog, whether REau may connect to it, and the request waits long enough for the tap (DashJoin).
     */
    DASH_JOIN_ASK,
    STREAMING,
    CARD,
    DASH_TAKEN,
    DASH_NAME_NEEDED,

    /** The network server is on: Android Auto goes to a browser on the network and the dash is not looked for. */
    NETWORK_SERVER,
}

/**
 * Where the projection stands: its [phase], and each of the two sides
 * on its own. [problem] is why the last start failed. With [card] the "Android Auto side" is the card mode's still
 * picture, which is up as soon as it is drawn; everything else runs the same.
 */
data class Projection(
    val phase: Phase = Phase.STOPPED,
    val androidAuto: AaLinkState = AaLinkState.IDLE,
    val dash: DashLink = DashLink.OFF,
    val dashRetries: Int = 0,
    /** When the dash went, while it is being tried again: the pace of the tries counts from here. */
    val dashGoneAtMs: Long? = null,
    /** The dash was ready at least once since this start: only then does a failed link mean it was lost. */
    val dashWasReady: Boolean = false,
    /** The dash's Wi-Fi being asked for has not been approved by the rider yet: Android's dialog is expected (DashJoin). */
    val dashFirstJoin: Boolean = false,
    val problem: String? = null,
    val card: Boolean = false,
    /**
     * The network server ("Ver no navegador") is on: REau does not look for the dash, since the
     * point is to run Android Auto on something that is not the dash.
     */
    val networkServer: Boolean = false,
    /** Android Auto's session is open and has sent no picture for a while ([com.reau.app.aa.VideoSilence]). */
    val aaNoVideo: Boolean = false,
    /** The bike moves and Android Auto has sent no new picture for a while ([com.reau.app.aa.StaleImage]). */
    val aaImageStale: Boolean = false,
    /**
     * The rider's Desligar was taken and its effects (video, Android Auto, dash, release) are still being carried out. The
     * [phase] is already [Phase.STOPPED]; a start is not taken until [ProjectionEvent.StopDone] clears this.
     */
    val stopping: Boolean = false,
) {
    /** Both sides up: Android Auto's picture goes to the dash. */
    val streaming: Boolean get() = phase == Phase.RUNNING && androidAuto == AaLinkState.ACTIVE && dash == DashLink.READY

    /**
     * Android Auto's session is open and sends no picture, and something is there to miss it: the dash is ready (it is black),
     * or the network server is on (the browser gets nothing). With no dash Android Auto has nobody to draw for and sends nothing
     * by itself, so force-stopping it would be advice for nothing: the dash side's own headline (searching) is what stands.
     */
    val noVideoAlarm: Boolean get() = !card && androidAuto == AaLinkState.ACTIVE && aaNoVideo && (dash == DashLink.READY || networkServer)

    val headline: Headline get() = when {
        phase == Phase.STOPPED && stopping -> Headline.STOPPING
        phase == Phase.STOPPED -> if (problem == null) Headline.STOPPED else Headline.PROBLEM
        phase == Phase.PREPARING -> Headline.PREPARING
        // The rider's to act on: the dash is black because Android Auto sends nothing.
        noVideoAlarm -> Headline.ANDROID_AUTO_NO_VIDEO
        streaming && !card && aaImageStale -> Headline.ANDROID_AUTO_IMAGE_STALE
        streaming -> if (card) Headline.CARD else Headline.STREAMING
        // What the rider can act on first: the server is switched on by hand, on the phone.
        androidAuto == AaLinkState.SERVER_OFF -> Headline.ANDROID_AUTO_SERVER_OFF
        // Also the rider's to act on, by force-stopping Android Auto.
        androidAuto == AaLinkState.UNRESPONSIVE -> Headline.ANDROID_AUTO_UNRESPONSIVE
        // Not the rider's to act on: it clears by itself and the receiver keeps dialling.
        androidAuto == AaLinkState.CLOSING_PREVIOUS -> Headline.ANDROID_AUTO_CLOSING_PREVIOUS
        // The rider's to act on too: opening Android Auto and finishing what it asks.
        androidAuto == AaLinkState.NOT_CALLING -> Headline.ANDROID_AUTO_NOT_CALLING
        networkServer -> Headline.NETWORK_SERVER
        // Also the rider's to act on: switching the other app's stream off.
        dash == DashLink.TAKEN -> Headline.DASH_TAKEN
        // The rider's to fix too: typing the dash's whole Wi-Fi name.
        dash == DashLink.NAME_NEEDED -> Headline.DASH_NAME_NEEDED
        // The Wi-Fi never joined from here: Android's own question is what the rider may be waiting to answer.
        dashFirstJoin && !dashWasReady && (dash == DashLink.CONNECTING || dash == DashLink.DROPPED) -> Headline.DASH_JOIN_ASK
        // "Lost" only after a connection that really existed; before it, the Wi-Fi is being looked for.
        dash == DashLink.DROPPED -> if (dashWasReady) Headline.DASH_LOST else Headline.DASH_SEARCHING
        else -> Headline.CONNECTING
    }
}

sealed interface ProjectionEvent {
    /** The rider switched it on. */
    data object Start : ProjectionEvent

    /** The rider switched on the card mode: a still picture to the dash, no Android Auto. */
    data object StartCard : ProjectionEvent

    /** The service is in the foreground with its locks. */
    data object Prepared : ProjectionEvent

    data class PrepareFailed(val reason: String) : ProjectionEvent

    /** The rider switched it off. */
    data object Stop : ProjectionEvent

    /** Every effect of a [Stop] has been carried out (or failed): the projection is stopped for good. */
    data object StopDone : ProjectionEvent

    data class AndroidAutoChanged(val state: AaLinkState) : ProjectionEvent

    data class DashChanged(val link: DashLink) : ProjectionEvent

    /** The wait asked for by [ProjectionEffect.RetryDashIn] is over. */
    data object DashRetryDue : ProjectionEvent

    /**
     * The bike's Wi-Fi is up (again). A dash dropped and waiting out its retry is tried at once instead of at the end of
     * the wait: the wait is for a dash that is not there, and the Wi-Fi coming back says it is.
     */
    data object BikeNetworkAvailable : ProjectionEvent

    /**
     * The Wi-Fi link says whether the dash's network being asked for was never approved by the rider ([on]; Android's dialog is
     * expected), or that it is joined or no longer in question (false).
     */
    data class DashFirstJoin(val on: Boolean) : ProjectionEvent

    /** The network server was switched on or off while the projection runs. */
    data class NetworkServer(val on: Boolean) : ProjectionEvent

    /** Android Auto's open session sends no picture ([silent]), or the picture came. */
    data class AndroidAutoVideo(val silent: Boolean) : ProjectionEvent

    /** Android Auto sends no new picture while the bike moves ([stale]), or the picture is alive again. */
    data class AndroidAutoImageStale(val stale: Boolean) : ProjectionEvent
}

sealed interface ProjectionEffect {
    data object Prepare : ProjectionEffect
    data object Release : ProjectionEffect
    data object StartAndroidAuto : ProjectionEffect
    data object StopAndroidAuto : ProjectionEffect
    data object StartDash : ProjectionEffect
    data object StopDash : ProjectionEffect
    data object StartVideo : ProjectionEffect
    data object StopVideo : ProjectionEffect

    /** The last effect of a stop: send [ProjectionEvent.StopDone], whatever the earlier ones did. */
    data object FinishStop : ProjectionEffect

    /** Send [ProjectionEvent.DashRetryDue] after [delayMs]. */
    data class RetryDashIn(val delayMs: Long) : ProjectionEffect

    /** The wait asked for by [RetryDashIn] is not wanted any more: the dash is being tried now. */
    data object CancelDashRetry : ProjectionEffect
}

data class Step(val state: Projection, val effects: List<ProjectionEffect>)

/**
 * The rules of the projection's life, and nothing else: no thread, no clock, no Android. Given where things stand
 * and what happened, [step] says where they stand now and what is to be done.
 *
 * - Switched on, the service is prepared first; once it is, Android Auto is started, and the dash only once Android
 *   Auto's session is up (Android Auto is made sure of before connecting to the dash). The
 *   card mode, with no Android Auto, starts both at once.
 * - The two sides fall each on its own. Android Auto's receiver dials again by itself, and the dash keeps getting
 *   video meanwhile (the compositor repeats the last picture). The dash is connected again here, after a wait that
 *   doubles each time up to [MAX_DASH_RETRY_MS]; its video stops while it is away.
 * - The video to the dash runs while the dash is ready, whether Android Auto is there or not.
 * - Reconnecting happens only while the rider has the projection on: nothing starts by itself.
 * - With the network server on, the dash is not looked for at all: switched on, the dash is let
 *   go and nothing about it is heard; switched off, it is looked for again.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
object ProjectionMachine {
    private const val FIRST_DASH_RETRY_MS = 2_000L
    private const val MAX_DASH_RETRY_MS = 30_000L

    // Five minutes trying at most every half minute, then once a minute, no end.
    private const val SLOW_AFTER_MS = 5 * 60_000L
    private const val SLOW_DASH_RETRY_MS = 60_000L

    fun step(state: Projection, event: ProjectionEvent, nowMs: Long = 0L): Step = when (event) {
        ProjectionEvent.Start -> start(state, card = false)
        ProjectionEvent.StartCard -> start(state, card = true)
        ProjectionEvent.Prepared -> prepared(state)
        is ProjectionEvent.PrepareFailed -> prepareFailed(state, event.reason)
        ProjectionEvent.Stop -> stop(state)
        ProjectionEvent.StopDone -> if (state.stopping) Step(Projection(), emptyList()) else Step(state, emptyList())
        // Android Auto's state; its session up with the dash still waiting for it (OFF) starts the dash.
        // A session that goes away takes its stale picture with it.
        is ProjectionEvent.AndroidAutoChanged -> ProjectionNetworkRule.androidAutoChanged(state, event.state).let { next ->
            if (event.state != AaLinkState.ACTIVE) next.copy(state = next.state.copy(aaImageStale = false)) else next
        }
        is ProjectionEvent.DashChanged -> dashChanged(state, event.link, nowMs)
        ProjectionEvent.DashRetryDue -> dashRetryDue(state)
        ProjectionEvent.BikeNetworkAvailable -> bikeNetworkAvailable(state)
        is ProjectionEvent.DashFirstJoin -> Step(state.copy(dashFirstJoin = event.on && state.phase == Phase.RUNNING), emptyList())
        is ProjectionEvent.NetworkServer -> ProjectionNetworkRule.switched(state, event.on)
        is ProjectionEvent.AndroidAutoVideo -> Step(state.copy(aaNoVideo = event.silent && state.phase == Phase.RUNNING), emptyList())
        is ProjectionEvent.AndroidAutoImageStale -> Step(state.copy(aaImageStale = event.stale && state.phase == Phase.RUNNING), emptyList())
    }

    /**
     * The wait before the [attempt]th try to get a dropped dash back (1 is the first), the dash gone for [goneMs]:
     * doubling from 2 s up to half a minute in the first five minutes, then once a minute.
     */
    fun dashRetryDelayMs(attempt: Int, goneMs: Long = 0L): Long {
        if (goneMs >= SLOW_AFTER_MS) return SLOW_DASH_RETRY_MS
        val doublings = (attempt - 1).coerceIn(0, MAX_DOUBLINGS)
        return (FIRST_DASH_RETRY_MS shl doublings).coerceAtMost(MAX_DASH_RETRY_MS)
    }

    /** The dash session's own states, read as the four the machine knows. */
    /**
     * The dash session's states as links for the machine, without the IDLE it starts in: a state flow
     * hands its first listener its current value, and that listener starts on another thread, so the starting IDLE
     * could arrive after the machine had asked for the dash and be read as a drop (a retry that cancelled the Wi-Fi
     * request under way). An IDLE after any activity is a real drop and goes through.
     */
    fun dashLinks(states: Flow<DashState>): Flow<DashLink> = states.dropWhile { it == DashState.IDLE }.map(::linkOf)

    fun linkOf(state: DashState): DashLink = when (state) {
        DashState.IDLE -> DashLink.OFF
        DashState.CONNECTING, DashState.AUTHENTICATING -> DashLink.CONNECTING
        DashState.READY, DashState.STREAMING -> DashLink.READY
        DashState.ERROR -> DashLink.DROPPED
    }

    private fun start(state: Projection, card: Boolean): Step =
        // Not while the last stop is still being carried out: the sides it is letting go would be started over.
        if (state.phase != Phase.STOPPED || state.stopping) {
            Step(state, emptyList())
        } else {
            Step(Projection(phase = Phase.PREPARING, card = card), listOf(ProjectionEffect.Prepare))
        }

    private fun prepared(state: Projection): Step =
        if (state.phase != Phase.PREPARING) {
            Step(state, emptyList())
        } else {
            val running = state.copy(phase = Phase.RUNNING, androidAuto = AaLinkState.CONNECTING)
            if (state.card) {
                Step(running.copy(dash = DashLink.CONNECTING), listOf(ProjectionEffect.StartAndroidAuto, ProjectionEffect.StartDash))
            } else {
                // The dash waits (OFF) for Android Auto's session: see AndroidAutoChanged in step.
                Step(running.copy(dash = DashLink.OFF), listOf(ProjectionEffect.StartAndroidAuto))
            }
        }

    private fun prepareFailed(state: Projection, reason: String): Step =
        if (state.phase != Phase.PREPARING) {
            Step(state, emptyList())
        } else {
            Step(Projection(problem = reason), listOf(ProjectionEffect.Release))
        }

    private fun stop(state: Projection): Step {
        val effects = when (state.phase) {
            Phase.STOPPED -> return Step(state, emptyList())
            Phase.PREPARING -> listOf(ProjectionEffect.Release)
            Phase.RUNNING -> listOfNotNull(
                ProjectionEffect.StopVideo.takeIf { state.dash == DashLink.READY },
                ProjectionEffect.StopAndroidAuto,
                ProjectionEffect.StopDash,
                ProjectionEffect.Release,
            )
        }
        // Stopped at once for everything that reads the phase; "stopping" says the effects are still going on.
        return Step(Projection(stopping = true), effects + ProjectionEffect.FinishStop)
    }

    private fun dashChanged(state: Projection, reported: DashLink, nowMs: Long): Step {
        if (state.phase != Phase.RUNNING || state.networkServer) return Step(state, emptyList())
        // Idle while running is not something this machine asked for: the session went away by itself.
        val link = if (reported == DashLink.OFF) DashLink.DROPPED else reported
        if (link == state.dash) return Step(state, emptyList())
        val stopVideo = listOfNotNull(ProjectionEffect.StopVideo.takeIf { state.dash == DashLink.READY })
        return when (link) {
            DashLink.READY -> Step(
                state.copy(dash = link, dashRetries = 0, dashGoneAtMs = null, dashWasReady = true, dashFirstJoin = false),
                listOf(ProjectionEffect.StartVideo),
            )
            // Taken by another app: tried again on the same timer, so it comes up by itself once the other lets go.
            DashLink.DROPPED, DashLink.TAKEN -> {
                val attempt = state.dashRetries + 1
                val goneAt = state.dashGoneAtMs ?: nowMs
                val wait = dashRetryDelayMs(attempt, goneMs = nowMs - goneAt)
                Step(state.copy(dash = link, dashRetries = attempt, dashGoneAtMs = goneAt), stopVideo + ProjectionEffect.RetryDashIn(wait))
            }
            // No full name: not tried again by itself, since each try by the prefix opens Android's network picker.
            else -> Step(state.copy(dash = link), stopVideo)
        }
    }

    private fun dashRetryDue(state: Projection): Step =
        // Only a running projection has a dropped dash: stopping clears the state, and the dash is not heard before.
        if (state.dash != DashLink.DROPPED && state.dash != DashLink.TAKEN) {
            Step(state, emptyList())
        } else if (!state.card && state.androidAuto != AaLinkState.ACTIVE) {
            // Android Auto is down: the dash waits for it again (AndroidAutoChanged in step starts it).
            Step(state.copy(dash = DashLink.OFF), emptyList())
        } else {
            Step(state.copy(dash = DashLink.CONNECTING), listOf(ProjectionEffect.StartDash))
        }

    /**
     * The bike's Wi-Fi is back while a dropped dash waits for its retry: the wait is cut and the dash is tried now,
     * and the pace of the waits starts over (the next failure waits 2 s again, not the minute of a long absence).
     * A dash being connected, ready, taken by another app (the Wi-Fi is not what it waits for) or not running at
     * all is none of this event's business.
     */
    private fun bikeNetworkAvailable(state: Projection): Step {
        if (state.phase != Phase.RUNNING || state.dash != DashLink.DROPPED) return Step(state, emptyList())
        val due = dashRetryDue(state)
        return Step(
            due.state.copy(dashRetries = 0, dashGoneAtMs = null),
            listOf(ProjectionEffect.CancelDashRetry) + due.effects,
        )
    }

    // 2 s doubled four times is past the ceiling already.
    private const val MAX_DOUBLINGS = 4
}
