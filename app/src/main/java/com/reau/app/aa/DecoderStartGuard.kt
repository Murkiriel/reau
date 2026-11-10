package com.reau.app.aa

/**
 * What the video decoder does after a start that failed.
 *
 * `decode()` tries to start a decoder on every picture while there is none, so a codec that refuses to configure
 * (a busy hardware decoder, a wrong surface) was tried again 30 times a second, each try creating a codec. Now a failure
 * waits [backoffMs] before the next try (250 ms, 1 s, 4 s, then 10 s), and from the [SOFTWARE_AFTER]th failure in a row
 * the software decoder is asked for instead of the hardware one. A start that works, or a new surface, begins again.
 *
 * A decoder can also start without a word and then give no picture (a hardware decoder that takes the input and never
 * answers). Each such stall restarts it, and the restart starts it well, so the failures never add up. The stalls are counted
 * apart: from the [STALLS_BEFORE_SOFTWARE]th the software decoder is asked for, for the rest of the guard's life (a start that
 * works does not forgive them; only a new surface does, and a new session has a new guard).
 *
 * Called only with the decoder's lock held.
 */
internal class DecoderStartGuard {
    private var failures = 0
    private var stalls = 0
    private var retryAtMs = 0L

    /** True when the next start should ask for the software decoder. */
    val useSoftware: Boolean get() = failures >= SOFTWARE_AFTER || stalls >= STALLS_BEFORE_SOFTWARE

    val failuresInARow: Int get() = failures

    /**
     * Whether to ask the decoder for its low-latency mode (`KEY_LOW_LATENCY`, Android 11+): on the first try only. A decoder that
     * refuses to configure with it is tried again without it, then in software.
     */
    val lowLatency: Boolean get() = failures == 0

    fun mayTry(nowMs: Long): Boolean = failures == 0 || nowMs >= retryAtMs

    fun failed(nowMs: Long) {
        failures++
        retryAtMs = nowMs + backoffMs(failures)
    }

    /** A decoder that started was restarted for giving no picture. True the one time this makes the next start a software one. */
    fun stalled(): Boolean {
        stalls++
        return stalls == STALLS_BEFORE_SOFTWARE
    }

    /** A start that worked: the failures are forgiven, the stalls are not. */
    fun worked() {
        failures = 0
        retryAtMs = 0L
    }

    /** A new surface: everything begins again. */
    fun reset() {
        worked()
        stalls = 0
    }

    companion object {
        const val SOFTWARE_AFTER = 2

        /** The hardware decoder that starts and then stalls this many times is given up for the software one (as in ridescreen-aa). */
        const val STALLS_BEFORE_SOFTWARE = 2

        /** The wait after the [failures]th failure in a row. */
        fun backoffMs(failures: Int): Long = when {
            failures <= 1 -> 250L
            failures == 2 -> 1_000L
            failures == 3 -> 4_000L
            else -> 10_000L
        }
    }
}
