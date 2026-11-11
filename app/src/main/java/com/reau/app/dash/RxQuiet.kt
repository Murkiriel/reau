package com.reau.app.dash

import java.util.Locale

/**
 * The tolerant watch of the dash's silence, on the experimental switch ([com.reau.app.settings.REauSettings.experimentTolerantRx]). Today 4 s without a
 * packet from the dash end the session ([DashSession.RX_WATCHDOG_MS]; it takes at least [DashSession.MIN_HEARTBEATS_BEFORE_SILENT] heartbeats sent), and the
 * next session costs a new authentication and a new keyframe, where another receiver for this dash waits 60 s. With the switch on, the session ends only at
 * [GIVE_UP_MS]; a silence of [NOTE_MS] is only noted in the ride log, once; and when the dash speaks again after a silence that was noted, [Kind.BACK] says so
 * (the session asks the video for a keyframe: the dash may have missed the ones sent meanwhile).
 *
 * One object per session's heartbeat loop, asked once a second. It decides nothing about ending the session; that stays with [DashSession.rxSilenceFails].
 */
internal class RxQuiet {
    enum class Kind { QUIET, BACK }

    /** What the look found: [kind], and the silence in milliseconds so far ([Kind.QUIET]) or that ended ([Kind.BACK]). */
    data class Verdict(val kind: Kind, val ms: Long)

    private var noted = false
    private var notedRxMs = 0L

    /** Forgets a silence that was noted (the switch went off, or the session left streaming). */
    fun reset() {
        noted = false
    }

    /**
     * The look at [nowMs]: the dash last spoke at [lastRxMs] (0: never), [streaming] says whether the session streams, and [heartbeatsSinceRx] is how many
     * heartbeats the phone sent since ([HeartbeatsSinceRx]: a silence counts only after the phone has been sending). Null: nothing to say.
     */
    fun look(nowMs: Long, lastRxMs: Long, streaming: Boolean, heartbeatsSinceRx: Int, noteMs: Long = NOTE_MS): Verdict? {
        if (!streaming || lastRxMs <= 0L) {
            reset()
            return null
        }
        return if (noted) back(lastRxMs) else quietNow(nowMs, lastRxMs, heartbeatsSinceRx, noteMs)
    }

    /** A noted silence ends when the dash's last packet is another one than the one the silence was noted after. */
    private fun back(lastRxMs: Long): Verdict? {
        if (lastRxMs == notedRxMs) return null
        noted = false
        return Verdict(Kind.BACK, lastRxMs - notedRxMs)
    }

    private fun quietNow(nowMs: Long, lastRxMs: Long, heartbeatsSinceRx: Int, noteMs: Long): Verdict? {
        if (nowMs - lastRxMs <= noteMs || heartbeatsSinceRx < DashSession.MIN_HEARTBEATS_BEFORE_SILENT) return null
        noted = true
        notedRxMs = lastRxMs
        return Verdict(Kind.QUIET, nowMs - lastRxMs)
    }

    companion object {
        /** A silence this long is noted in the ride log. */
        const val NOTE_MS = 12_000L

        /** A silence this long ends the session. */
        const val GIVE_UP_MS = 60_000L

        fun quietLine(ms: Long): String =
            "RX quiet: no dash packets for ${seconds(ms)} s; the session goes on and ends at ${GIVE_UP_MS / 1000} s of silence (tolerant watch, experiment)"

        fun backLine(ms: Long): String =
            "RX back: the dash spoke again after ${seconds(ms)} s of silence; a keyframe is asked (tolerant watch, experiment)"

        private fun seconds(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0)
    }
}

/**
 * The control packets of the dash's socket [gapMs] apart, on the same experimental switch: a packet is held until [gapMs] have passed since the
 * one before it went out (the other receiver for this dash spaces them 12 ms). With [gapMs] 0 (the default, switch off) nothing ever waits. One object
 * per socket; the control channel is sent on from several threads, hence synchronized.
 */
internal class ControlGap(private val gapMs: Long = 0L) {
    private var nextNs = Long.MIN_VALUE

    /** How long to wait, in nanoseconds, before sending a packet at [nowNs]; the slot after it is reserved. */
    @Synchronized
    fun waitNs(nowNs: Long): Long {
        if (gapMs <= 0L) return 0L
        val slot = maxOf(nowNs, nextNs)
        nextNs = slot + gapMs * NS_PER_MS
        return slot - nowNs
    }

    companion object {
        /** The gap the switch asks for. */
        const val GAP_MS = 12L
        private const val NS_PER_MS = 1_000_000L
    }
}
