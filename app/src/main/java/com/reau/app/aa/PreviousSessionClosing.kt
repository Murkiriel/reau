package com.reau.app.aa

/**
 * Right after REau's own clean stop, Android Auto's head unit server keeps listening for about 70 s (measured on a
 * phone, Android Auto 17.8) and accepts the next connection without answering the handshake: it is still closing the
 * session the stop ended. Three unanswered handshakes there are not a stuck Android Auto and do not call for a force
 * stop: it clears by itself, and the receiver keeps dialling. Inside [WINDOW_MS] of the stop the receiver says
 * [AaLinkState.CLOSING_PREVIOUS] where it would say [AaLinkState.UNRESPONSIVE]. The same goes for a stop REau did not see
 * (the other edition's, [maybeClosing]), counted from where this process began to dial.
 */
object PreviousSessionClosing {
    /** From the stop, with margin over the ~70 s measured (the port closed 70 s after the end of the session). */
    const val WINDOW_MS = 120_000L

    /** Whether [nowMs] is inside the window after the stop at [stoppedAtMs] (both elapsed realtime); no stop is no window. */
    fun within(nowMs: Long, stoppedAtMs: Long?): Boolean = stoppedAtMs != null && nowMs - stoppedAtMs in 0..WINDOW_MS

    /** How far the wall clock and the elapsed clock may disagree about the time since the stop (NTP adjusting, rounding). */
    const val CLOCK_SKEW_MS = 5_000L

    /**
     * The stop kept by a process that is gone ([savedWallMs] and [savedElapsedMs], read together at the stop), as an
     * elapsed-realtime instant of this process, or null when the window cannot be trusted and the old rule applies.
     * The elapsed clock starts again at each boot and the wall clock does not, and the wall clock may be set back: the
     * time since the stop must come out the same on both, within [CLOCK_SKEW_MS], else the phone restarted (Android Auto
     * was restarted with it, nothing is closing) or the clock moved. Whether it is still inside [WINDOW_MS] is [within]'s.
     */
    fun restoredStop(savedWallMs: Long, savedElapsedMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long? {
        val byElapsed = nowElapsedMs - savedElapsedMs
        val byWall = nowWallMs - savedWallMs
        if (byElapsed < 0 || byWall < 0) return null
        return if (kotlin.math.abs(byElapsed - byWall) <= CLOCK_SKEW_MS) savedElapsedMs else null
    }

    /**
     * Whether Android Auto may still be closing a session REau did not see end, counted from [maybeSinceMs]: the start of
     * the first receiver of this process that has had no handshake answered since (see [AaRestartGap.maybeClosingSinceMs]).
     * Another app can end a session on the same server just before REau starts: the other edition of REau, which shares the
     * port, was turned off a moment before this one was turned on (seen 18/10: the full edition came up a second after the
     * Lite's Desligar and had three mute rounds with no stop of its own to count from). Not an answer, only a doubt: it
     * ends after [WINDOW_MS] like the other, so a server that stays mute is still called stuck, and it is not renewed by
     * a receiver that starts inside the same mute stretch.
     */
    fun maybeClosing(nowMs: Long, maybeSinceMs: Long?): Boolean = within(nowMs, maybeSinceMs)

    /**
     * What to say when the handshakes in a row went unanswered: [AaLinkState.CLOSING_PREVIOUS] inside the window of
     * the last stop ([stoppedAtMs]) or of the doubt of [maybeClosing] ([maybeSinceMs]), as long as this receiver has not had a
     * handshake answered ([answeredSinceStart]: an Android Auto that answered and then stopped answering is stuck, not
     * closing), else [AaLinkState.UNRESPONSIVE]. A REau ended in the middle of a session (a new process, [maybeSinceMs]
     * null) has no stop to count from, so it is the second.
     */
    fun unansweredState(nowMs: Long, stoppedAtMs: Long?, answeredSinceStart: Boolean, maybeSinceMs: Long? = null): AaLinkState =
        if (!answeredSinceStart && (within(nowMs, stoppedAtMs) || maybeClosing(nowMs, maybeSinceMs))) AaLinkState.CLOSING_PREVIOUS
        else AaLinkState.UNRESPONSIVE

    /**
     * Whether the round of unanswered handshakes that says [next] is the one where the wait for the previous session to
     * close ran out: the round before said [AaLinkState.CLOSING_PREVIOUS] ([previous]) and this one says
     * [AaLinkState.UNRESPONSIVE]. The moment the one restart of the server becomes due (see [ServerPolicy]).
     */
    fun windowEnded(previous: AaLinkState?, next: AaLinkState): Boolean =
        previous == AaLinkState.CLOSING_PREVIOUS && next == AaLinkState.UNRESPONSIVE

    /**
     * What to say when a dial is accepted, the receiver being in [current]. Always [AaLinkState.CONNECTING], except that a
     * state already said for a server that accepts and does not answer stays while it still holds: every new round of dials is
     * accepted and left unanswered again, and going back to "connecting" for each one made the notice flip between the two
     * (for ~50 s on a phone, Android Auto 17.8, 18/10; and, as "not responding", for as long as it lasted).
     * - [AaLinkState.CLOSING_PREVIOUS] stays inside the window of the stop or of the doubt ([maybeSinceMs]), no handshake
     *   answered yet; the window running out ends it (the next unanswered handshake says [AaLinkState.UNRESPONSIVE]).
     * - [AaLinkState.UNRESPONSIVE] stays whatever the clock says: only an answer ends it (ACTIVE reports itself), or the port
     *   refused (SERVER_OFF), or the receiver stopping.
     */
    fun stateOnAccept(
        current: AaLinkState,
        nowMs: Long,
        stoppedAtMs: Long?,
        answeredSinceStart: Boolean,
        maybeSinceMs: Long? = null,
    ): AaLinkState = when {
        current == AaLinkState.UNRESPONSIVE -> current
        current == AaLinkState.CLOSING_PREVIOUS && !answeredSinceStart &&
            (within(nowMs, stoppedAtMs) || maybeClosing(nowMs, maybeSinceMs)) -> current
        else -> AaLinkState.CONNECTING
    }
}
