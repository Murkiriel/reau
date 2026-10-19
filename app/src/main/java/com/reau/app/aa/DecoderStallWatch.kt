package com.reau.app.aa

/**
 * Tells a video decoder that stopped giving pictures from Android Auto simply not sending any.
 *
 * Android Auto sends pictures only while its screen changes, and stops for seconds at a time (a still map, its app
 * list, a call). So the decoder counts as stalled only when an input has waited more than [stallMs] for an output
 * while input kept coming: the wait starts at the first input after an output or after a pause (no input for
 * [pauseMs]), and silence from the phone is never a stall.
 *
 * "Input" is what the phone offered, whether or not the codec took it: a codec whose input queue is full and that
 * gives nothing out is stalled too.
 *
 * It watches from the moment a decoder opens ([reset]), not from its first picture: a decoder that never gives one
 * is the worst stall there is.
 *
 * [onInput] and [reset] are called by the thread that feeds the decoder, [onOutput] and [isStalled] by the one that
 * drains it.
 */
internal class DecoderStallWatch(private val stallMs: Long = STALL_MS, private val pauseMs: Long = PAUSE_MS) {
    // 0: no input is waiting for an output.
    @Volatile private var waitingSinceMs = 0L
    @Volatile private var lastInputMs = 0L

    fun onInput(nowMs: Long) {
        if (waitingSinceMs == 0L || nowMs - lastInputMs >= pauseMs) waitingSinceMs = nowMs
        lastInputMs = nowMs
    }

    fun onOutput() {
        waitingSinceMs = 0L
    }

    /** A decoder was just opened: it owes nothing for the input that came before it. */
    fun reset() {
        waitingSinceMs = 0L
    }

    fun isStalled(nowMs: Long): Boolean = waitingMs(nowMs) > stallMs && inputGapMs(nowMs) < pauseMs

    /** How long the oldest unanswered input has waited; 0 when none is waiting. */
    fun waitingMs(nowMs: Long): Long = waitingSinceMs.let { if (it == 0L) 0L else nowMs - it }

    /** How long ago the last input came. */
    fun inputGapMs(nowMs: Long): Long = nowMs - lastInputMs

    companion object {
        const val STALL_MS = 3_000L
        const val PAUSE_MS = 1_000L
    }
}
