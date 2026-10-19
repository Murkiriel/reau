package com.reau.app.dash.video

import android.media.MediaCodec

/**
 * A thread that takes every output buffer of a running encoder the moment the codec has it and
 * hands it to [emit], until [stop]. It is what lets the frame loop submit a frame and move on
 * instead of waiting for it to come back encoded (see [DashEncoder]'s header).
 *
 * [emit] receives the buffer index and its info, and must release the buffer.
 */
internal class EncoderOutputPump(
    private val codec: MediaCodec,
    private val emit: (Int, MediaCodec.BufferInfo) -> Unit,
) {
    private var worker: Thread? = null
    @Volatile private var stopping = false
    // The codec itself failed: stays set, this encoder is finished.
    @Volatile private var codecError: Throwable? = null
    // [emit] threw (e.g. a send failed): the thread carries on, the error is reported once.
    @Volatile private var emitError: Throwable? = null

    fun start() {
        worker = Thread({
            val info = MediaCodec.BufferInfo()
            try {
                while (!stopping) {
                    val idx = codec.dequeueOutputBuffer(info, POLL_US)
                    if (idx >= 0) runCatching { emit(idx, info) }.onFailure { if (!stopping) emitError = it }
                }
            } catch (e: IllegalStateException) {
                // What a failed codec throws (CodecException included). The codec being stopped
                // under a pending dequeue lands here too — only a failure nobody asked for is an error.
                if (!stopping) codecError = e
            }
        }, THREAD_NAME).also { it.start() }
    }

    /**
     * Throws what went wrong on the thread since the last call, so the frame loop's own "N failures
     * in a row → rebuild the encoder" recovery still sees it. A codec failure keeps being thrown:
     * the thread is gone and only a rebuild helps.
     */
    fun check() {
        codecError?.let { throw IllegalStateException("encoder output failed: ${it.message}", it) }
        val e = emitError ?: return
        emitError = null
        throw IllegalStateException("sending an encoded frame failed: ${e.message}", e)
    }

    /** Ends the thread and waits for it, so nothing more is emitted once this returns (bounded:
     *  an [emit] stuck in a send is left behind and dies with the codec). Never joins itself. */
    fun stop() {
        stopping = true
        worker?.let { t -> if (t !== Thread.currentThread()) runCatching { t.join(JOIN_MS) } }
        worker = null
    }

    companion object {
        const val THREAD_NAME = "DashEncoderOut"
        // How long the thread sleeps in the codec waiting for a buffer. A ready buffer returns at once, so this adds
        // no latency; it sets how often an idle encoder wakes (10 a second, not 50) and how long stop() normally
        // takes to be noticed, which stays under JOIN_MS.
        private const val POLL_US = 100_000L
        private const val JOIN_MS = 200L
    }
}
