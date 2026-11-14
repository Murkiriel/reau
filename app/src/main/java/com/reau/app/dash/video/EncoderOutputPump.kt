package com.reau.app.dash.video

import android.media.MediaCodec
import android.media.MediaFormat
import com.reau.app.util.CodecErrors

/**
 * A thread that takes every output buffer of a running encoder the moment the codec has it and
 * hands it to [emit], until [stop]. It is what lets the frame loop submit a frame and move on
 * instead of waiting for it to come back encoded (see [DashEncoder]'s header).
 *
 * [emit] receives the buffer index and its info, and must release the buffer.
 */
internal class EncoderOutputPump(
    private val dequeue: (MediaCodec.BufferInfo) -> Int,
    /** The codec's name, for the line that says it failed ([CodecErrors.describe]); null when not known. */
    private val codecName: String? = null,
    private val emit: (Int, MediaCodec.BufferInfo) -> Unit,
) {
    /**
     * [onFormat] is told the codec's output format when it changes (once, before the first buffer; the ride log keeps it), on the
     * output thread; a failure of it is no failure of the codec.
     */
    constructor(codec: MediaCodec, onFormat: (MediaFormat) -> Unit = {}, emit: (Int, MediaCodec.BufferInfo) -> Unit) : this(
        { info -> dequeueTelling(codec, info, onFormat) },
        nameOf(codec),
        emit,
    )

    private var worker: Thread? = null
    // Set when the thread has left its loop; [afterEnd] is what [stopThen] left to run when it does (once, by whoever comes second).
    private val ended = java.util.concurrent.atomic.AtomicBoolean(false)
    private val afterEnd = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
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
                    val idx = dequeue(info)
                    if (idx >= 0) runCatching { emit(idx, info) }.onFailure { if (!stopping) emitError = it }
                }
            } catch (e: IllegalStateException) {
                // What a failed codec throws (CodecException included). The codec being stopped
                // under a pending dequeue lands here too — only a failure nobody asked for is an error.
                if (!stopping) codecError = e
            } finally {
                ended.set(true)
                afterEnd.getAndSet(null)?.invoke()
            }
        }, THREAD_NAME).also { it.start() }
    }

    /**
     * Throws what went wrong on the thread since the last call, so the frame loop's own "N failures
     * in a row → rebuild the encoder" recovery still sees it. A codec failure keeps being thrown:
     * the thread is gone and only a rebuild helps.
     */
    fun check() {
        codecError?.let { throw IllegalStateException("encoder output failed: ${CodecErrors.describe(it, codecName)}", it) }
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

    /**
     * Ends the thread like [stop], and then runs [then] (what lets go of the codec): at once when the thread is out, or on the
     * thread itself the moment it leaves its loop when an [emit] kept it past the wait. The codec is thus never stopped under a
     * thread that may still be inside it (a native crash on some phones: audit of 20/10/2026); [then] runs exactly once.
     */
    fun stopThen(then: () -> Unit) {
        stopping = true
        val t = worker
        worker = null
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(JOIN_MS) }
        if (t == null || t === Thread.currentThread() || !t.isAlive) {
            then()
            return
        }
        afterEnd.set(then)
        // It may have left between the check and the set: then nobody else will run it.
        if (ended.get()) afterEnd.getAndSet(null)?.invoke()
    }

    companion object {
        const val THREAD_NAME = "DashEncoderOut"

        // Helpers of the secondary constructor (a constructor's delegating call has no `this` to run runCatching on).
        private fun nameOf(codec: MediaCodec): String? = runCatching { codec.name }.getOrNull()

        private fun dequeueTelling(codec: MediaCodec, info: MediaCodec.BufferInfo, onFormat: (MediaFormat) -> Unit): Int =
            codec.dequeueOutputBuffer(info, POLL_US).also {
                if (it == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) runCatching { onFormat(codec.outputFormat) }
            }

        /**
         * What an encoder's release() does with its pump and its codec: [letGo] runs at once when there is no pump, else through
         * [stopThen], so the codec never goes under an output thread that may still be inside it. Both encoders (dash and web) use it.
         */
        fun releaseThrough(pump: EncoderOutputPump?, letGo: () -> Unit) {
            if (pump == null) letGo() else pump.stopThen(letGo)
        }

        // How long the thread sleeps in the codec waiting for a buffer. A ready buffer returns at once, so this adds
        // no latency; it sets how often an idle encoder wakes (10 a second, not 50) and how long stop() normally
        // takes to be noticed, which stays under JOIN_MS.
        private const val POLL_US = 100_000L
        private const val JOIN_MS = 200L
    }
}
