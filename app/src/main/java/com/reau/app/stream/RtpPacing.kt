package com.reau.app.stream

import java.util.concurrent.locks.LockSupport

/**
 * The experimental spacing of the video's RTP packets ([com.reau.app.settings.REauSettings.experimentRtpPacing]). A keyframe leaves the encoder
 * as a dozen packets in a row, a burst the dash's small Wi-Fi access point has to take at once. With this on, the thread that sends them waits
 * [PAUSE_NANOS] after every [PACKETS_PER_PAUSE] packets of a frame, and no more than [MAX_PER_SECOND] go out in a second: at that count the
 * thread waits for the second to end.
 *
 * It runs on the thread that already sends the packets (the encoder's output thread), between two sends: no queue and no thread of its own.
 * [now] and [pause] are passed in so a plain JVM test runs it without waiting.
 *
 * Use: [frameStart] when a frame's first packet is about to be made, [before] ahead of each send and [after] when it has gone.
 */
internal class RtpPacing(
    private val now: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = LockSupport::parkNanos,
) {
    private var inFrame = 0
    private var windowStartNs = 0L
    private var inWindow = 0
    private var windowOpen = false

    /** Packets that waited for the second to end, and the nanoseconds waited in all (for the ride log's line when the stream ends). */
    @Volatile var capHits = 0
        private set
    @Volatile var waitedNs = 0L
        private set

    /** A new frame begins: its packets are counted from the first again. */
    fun frameStart() {
        inFrame = 0
    }

    /** Ahead of a send: with [MAX_PER_SECOND] already gone in the second, waits for it to end and starts the next one. */
    fun before() {
        val t = now()
        if (!windowOpen || t - windowStartNs >= WINDOW_NANOS) {
            windowStartNs = t
            inWindow = 0
            windowOpen = true
        }
        if (inWindow >= MAX_PER_SECOND) {
            val wait = WINDOW_NANOS - (t - windowStartNs)
            if (wait > 0) {
                capHits++
                waitedNs += wait
                pause(wait)
            }
            windowStartNs = now()
            inWindow = 0
        }
    }

    /** After a send: counts the packet, and waits [PAUSE_NANOS] when it was the [PACKETS_PER_PAUSE]th of the frame (and the fourth, eighth... after it). */
    fun after() {
        inWindow++
        inFrame++
        if (inFrame % PACKETS_PER_PAUSE == 0) {
            waitedNs += PAUSE_NANOS
            pause(PAUSE_NANOS)
        }
    }

    companion object {
        /** The spacing the experimental switch asks for: one when it is [on], none (the packets leave as they are made) when it is off. */
        fun whenOn(on: Boolean): RtpPacing? = if (on) RtpPacing() else null

        const val PACKETS_PER_PAUSE = 4
        const val PAUSE_NANOS = 2_000_000L
        const val MAX_PER_SECOND = 400
        private const val WINDOW_NANOS = 1_000_000_000L
    }
}
