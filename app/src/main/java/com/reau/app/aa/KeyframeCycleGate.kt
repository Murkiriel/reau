package com.reau.app.aa

/**
 * Decides when the session may cycle video focus to make Android Auto send a keyframe (the only way: the protocol has no
 * request for one). A cycle costs a restart of Android Auto's video, so a defect that asked for one over and over would
 * keep the picture down for good (open-headunit saw its cycles cut the frame rate on some phones).
 *
 * A cycle goes out only when:
 * - the last one was more than [SPACING_MS] ago, and
 * - the compositor the decoder draws into is not stuck inside a frame (`blocked`: a keyframe is no use to a consumer that
 *   cannot show it), and
 * - there were not already [STORM_CYCLES] cycles in the last [STORM_WINDOW_MS]; if there were, the next waits [STORM_HOLD_MS]
 *   after the last one.
 *
 * A request that may not go is kept, not lost (a lost one would leave the picture broken until Android Auto's own keyframe,
 * a minute away): [takePending] hands it out as soon as the rules allow, and the session asks for it every second.
 * Two requests waiting make one cycle.
 *
 * The idea is the one of open-headunit's commit 964bd7cb ("deferred, not lost"); the numbers are REau's own. Nothing of its
 * code is copied. Called from the read thread, the decoder's and the session's timer: every method is synchronized.
 */
internal class KeyframeCycleGate {
    /** What became of one request. */
    enum class Verdict { GO, WAIT_SPACING, WAIT_STORM, WAIT_BLOCKED }

    private val cycles = ArrayDeque<Long>()
    private var pending = false

    val isPending: Boolean
        @Synchronized get() = pending

    /** A keyframe is wanted now: [Verdict.GO] when the cycle goes out (and counts), else the reason it waits (and the request is kept). */
    @Synchronized
    fun request(nowMs: Long, blocked: Boolean = false): Verdict {
        val verdict = verdict(nowMs, blocked)
        if (verdict == Verdict.GO) {
            record(nowMs)
            pending = false
        } else {
            pending = true
        }
        return verdict
    }

    /** Whether a request that had to wait can go now (and then counts, and is no longer pending). */
    @Synchronized
    fun takePending(nowMs: Long, blocked: Boolean = false): Boolean {
        if (!pending || verdict(nowMs, blocked) != Verdict.GO) return false
        record(nowMs)
        pending = false
        return true
    }

    /** How long until the rules of time (not the compositor) allow another cycle; 0 when they already do. */
    @Synchronized
    fun waitLeftMs(nowMs: Long): Long {
        val last = cycles.lastOrNull() ?: return 0L
        val hold = if (recent(nowMs) >= STORM_CYCLES) STORM_HOLD_MS else SPACING_MS
        return (last + hold - nowMs).coerceAtLeast(0L)
    }

    private fun verdict(nowMs: Long, blocked: Boolean): Verdict {
        val last = cycles.lastOrNull()
        return when {
            blocked -> Verdict.WAIT_BLOCKED
            last == null -> Verdict.GO
            recent(nowMs) >= STORM_CYCLES -> if (nowMs - last >= STORM_HOLD_MS) Verdict.GO else Verdict.WAIT_STORM
            nowMs - last > SPACING_MS -> Verdict.GO
            else -> Verdict.WAIT_SPACING
        }
    }

    /** The cycles in the last [STORM_WINDOW_MS]; an older one is forgotten. */
    private fun recent(nowMs: Long): Int {
        while (cycles.isNotEmpty() && (nowMs - cycles.first() >= STORM_WINDOW_MS)) cycles.removeFirst()
        return cycles.size
    }

    private fun record(nowMs: Long) {
        recent(nowMs)
        cycles.addLast(nowMs)
    }

    companion object {
        /** Between two cycles, at least this and one millisecond more (the 1 s rule of [VideoRecoveryPolicy]). */
        const val SPACING_MS = VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS

        /** This many cycles inside [STORM_WINDOW_MS] make a storm. */
        const val STORM_CYCLES = 3
        const val STORM_WINDOW_MS = 30_000L

        /** In a storm, the wait after the last cycle. */
        const val STORM_HOLD_MS = 15_000L
    }
}
