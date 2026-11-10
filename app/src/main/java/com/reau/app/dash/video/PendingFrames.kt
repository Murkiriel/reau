package com.reau.app.dash.video

import com.reau.app.util.StageClock
import com.reau.app.util.StageStats

/**
 * Frames handed to the encoder that have not come out of it yet, oldest first — what lets every
 * encoded frame leave with the presentation time of ITS OWN submission when the output is read on
 * another thread, after the frame loop has already moved on. Observed in ride logs: a frame that
 * left a loop late took the next frame's time, two frames with one RTP timestamp.
 *
 * Also measures what the frame loop no longer waits for: how long each frame spent inside the
 * encoder, and how many came out only after a newer one had already gone in ("late").
 *
 * Plain logic with the clock passed in, so it is unit-tested; every method is synchronized because
 * the frame loop submits and the encoder's output thread emits.
 */
internal class PendingFrames {

    data class Emitted(val ptsMs: Long, val late: Boolean)

    data class Stats(val frames: Int, val late: Int, val latencySumNs: Long) {
        /**
         * The same time [latencySumNs] sums, frame by frame: its worst and 95th percentile go to the `video fps` line. Outside the
         * data class's own fields on purpose: what the counts say is still compared as it was.
         */
        var latency: StageStats = StageStats.EMPTY
            internal set
    }

    private val ptsMs = ArrayDeque<Long>()
    private val submittedNs = ArrayDeque<Long>()
    private var lastPtsMs = 0L
    private var frames = 0
    private var late = 0
    private var latencySumNs = 0L
    private val latencies = StageClock()

    /** Presentation time of the last frame that came out (0 before the first). */
    @Synchronized fun lastPtsMs(): Long = lastPtsMs

    /** One frame is about to be posted to the encoder's input. */
    @Synchronized
    fun submitted(framePtsMs: Long, nowNs: Long) {
        ptsMs.addLast(framePtsMs)
        submittedNs.addLast(nowNs)
    }

    /**
     * One encoded frame came out: returns the time it was submitted with. A frame the encoder
     * swallowed would leave its entry at the head for good and shift every later frame one slot
     * back, so entries waiting longer than [STALE_NS] with a newer one behind them are discarded
     * first (the encoder answers in tens of milliseconds).
     */
    @Synchronized
    fun emitted(nowNs: Long): Emitted {
        while (ptsMs.size > 1 && nowNs - submittedNs.first() > STALE_NS) {
            ptsMs.removeFirst(); submittedNs.removeFirst()
        }
        if (ptsMs.isEmpty()) return Emitted(lastPtsMs, late = false)
        lastPtsMs = ptsMs.removeFirst()
        val isLate = ptsMs.isNotEmpty()
        frames++
        if (isLate) late++
        val inEncoderNs = nowNs - submittedNs.removeFirst()
        latencySumNs += inEncoderNs
        latencies.add(inEncoderNs)
        return Emitted(lastPtsMs, isLate)
    }

    /** Totals since the previous call, then starts counting again. */
    @Synchronized
    fun takeStats(): Stats = Stats(frames, late, latencySumNs).also {
        it.latency = latencies.take()
        frames = 0; late = 0; latencySumNs = 0L
    }

    companion object {
        const val STALE_NS = 1_000_000_000L
    }
}
