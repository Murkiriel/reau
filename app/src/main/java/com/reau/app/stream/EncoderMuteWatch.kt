package com.reau.app.stream

/**
 * The experimental watch for an encoder gone mute ([com.reau.app.settings.REauSettings.experimentEncoderWatch]). The video loop rebuilds an
 * encoder whose output fails (an exception); one that takes frames in and simply gives nothing out, with no exception, was never noticed:
 * the dash keeps the last picture and the app says "streaming". This counts, poll by poll, the frames that went into the encoder and the
 * ones that came out: after [MUTE_MS] with at least [MIN_FRAMES] frames in and none out it asks for the encoder to be rebuilt, at most
 * [MAX_REBUILDS] times in a stream; after that it says once that it gives up and stays quiet.
 *
 * Plain logic with the clock passed in. A frame that comes out ends the wait, whatever came before.
 */
internal class EncoderMuteWatch {
    enum class Verdict { FINE, REBUILD, GIVE_UP }

    // When the encoder first held a frame without giving one out, in this wait; null: not waiting.
    private var sinceMs: Long? = null
    private var framesIn = 0
    private var gaveUp = false

    /** Rebuilds asked for in this stream. */
    var rebuilds = 0
        private set

    /** Frames that went in during the wait that just ended in a [Verdict.REBUILD] or [Verdict.GIVE_UP], for the log. */
    var lastWaitFrames = 0
        private set

    /** One poll of the loop at [nowMs]: [submitted] frames went into the encoder and [framesOut] came out of it since the poll before. */
    fun onPoll(nowMs: Long, submitted: Int, framesOut: Int): Verdict {
        if (framesOut > 0) {
            sinceMs = null
            framesIn = 0
        }
        if (framesOut > 0 || (submitted <= 0 && sinceMs == null)) return Verdict.FINE
        val since = sinceMs ?: nowMs.also { sinceMs = it }
        framesIn += submitted.coerceAtLeast(0)
        return if (nowMs - since < MUTE_MS || framesIn < MIN_FRAMES) Verdict.FINE else waitOver()
    }

    /** The wait reached its end with the encoder still mute: a rebuild, or, after the last one allowed, the word that it gives up, once. */
    private fun waitOver(): Verdict {
        lastWaitFrames = framesIn
        sinceMs = null
        framesIn = 0
        return when {
            gaveUp -> Verdict.FINE
            rebuilds >= MAX_REBUILDS -> Verdict.GIVE_UP.also { gaveUp = true }
            else -> Verdict.REBUILD.also { rebuilds++ }
        }
    }

    companion object {
        /** How long an encoder may hold frames without giving one out. Its own repeat of the last frame comes every 900 ms, so 3 s is three of those missed. */
        const val MUTE_MS = 3_000L

        /** Frames that must have gone in during the wait: one frame alone may be the one the encoder is still working on. */
        const val MIN_FRAMES = 3

        /** Rebuilds in one stream: an encoder still mute after them is left as it is. */
        const val MAX_REBUILDS = 3

        /** The watch the experimental switch asks for: one when it is [on], none (only a failing encoder is rebuilt, as always) when it is off. */
        fun whenOn(on: Boolean): EncoderMuteWatch? = if (on) EncoderMuteWatch() else null

        /** The ride log's line for a [verdict] that is not [Verdict.FINE]. */
        fun line(verdict: Verdict, framesIn: Int, rebuilds: Int): String = when (verdict) {
            Verdict.REBUILD ->
                "encoder mute (experiment): $framesIn frames went in and none came out for $MUTE_MS ms, with no error: " +
                    "rebuilding it ($rebuilds of $MAX_REBUILDS)"
            Verdict.GIVE_UP ->
                "encoder mute (experiment): $framesIn frames went in and none came out for $MUTE_MS ms again, " +
                    "after $MAX_REBUILDS rebuilds: left as it is"
            Verdict.FINE -> ""
        }
    }
}
