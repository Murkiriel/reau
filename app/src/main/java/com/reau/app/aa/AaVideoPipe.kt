package com.reau.app.aa

/**
 * The decoder fed on its own thread, [THREAD_NAME]. Android
 * Auto's read thread [put]s each access unit, which only copies it into a [FrameQueue], and goes back to reading
 * (and acknowledging) the next message; the decoder may hold its own thread up to its input timeout without holding
 * the read. With the queue full the read waits for a place up to [FULL_WAIT_MS], the most the decoder's input held it
 * before (30 tries of 10 ms, then the frame went nowhere): a decoder busy for a moment loses nothing, and one still
 * starting up has [FrameQueue.WARMING_LIMIT] places to wait in (see [limitFor]). Past that the decoder is behind: the frame is dropped and [onDropped] says it (the session counts it lost and asks for a keyframe, as for
 * a broken frame).
 *
 * The idea is the one of open-headunit's VideoDecoder.kt (AGPL-3.0, commit 93c065dd): a bounded queue of copied
 * frames and a feeding thread. Nothing of its code is copied.
 */
internal class AaVideoPipe(
    private val decode: (ByteArray, Int, Int) -> Unit,
    private val onDropped: () -> Unit,
    /** Says that the decoder threw on a frame (the frame is lost, as for a drop). */
    private val onFailed: () -> Unit = onDropped,
    /** Whether the decoder is up. Until it is, the queue takes [FrameQueue.WARMING_LIMIT] frames instead of [FrameQueue.CAPACITY]. */
    private val decoderReady: () -> Boolean = { true },
    private val queue: FrameQueue = FrameQueue(),
) {
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    /** Starts the feeding thread. */
    fun start() {
        running = true
        thread = Thread(::feed, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /** On the read thread: the frame copied into the queue, or dropped (and said) when the queue is full. */
    fun put(buffer: ByteArray, offset: Int, size: Int) {
        if (!running) return
        val limit = limitFor(decoderReady(), queue.waiting, queue.capacity)
        if (queue.limit != limit) queue.limit = limit
        if (!queue.offer(buffer, offset, size, FULL_WAIT_MS)) onDropped()
    }

    /**
     * Stops the feeding thread and drops what waits. It waits at most [joinMs] for the thread, which may be inside the
     * decoder; it never takes the decoder's lock itself.
     */
    fun stop(joinMs: Long = JOIN_MS) {
        running = false
        thread?.let {
            it.interrupt()
            runCatching { it.join(joinMs) }
        }
        thread = null
        queue.clear()
    }

    /** The feeding thread's loop; it ends quietly when [stop] interrupts it, waiting or inside the decoder. */
    private fun feed() {
        var going = true
        while (going && running) {
            going = try {
                queue.take(POLL_MS)?.let(::decodeOne) ?: true
            } catch (_: InterruptedException) {
                false
            }
        }
    }

    /** False when the thread was interrupted inside the decoder (by [stop]). */
    @Suppress("TooGenericExceptionCaught") // whatever the codec throws on one frame, the feed must go on
    private fun decodeOne(frame: FrameQueue.Frame): Boolean = try {
        if (running) decode(frame.data, 0, frame.size)
        true
    } catch (_: InterruptedException) {
        false
    } catch (e: Exception) {
        // One frame the decoder could not take must not end the feed. It is lost, though: the frames after it predict from it, so
        // a keyframe is asked for (waiting for Android Auto's own would leave the picture broken for up to a minute).
        AaLog.e("AaVideoPipe: the decoder failed on a frame", e)
        onFailed()
        true
    } finally {
        queue.recycle(frame)
    }

    internal fun testOnlyThreadAlive(): Boolean = thread?.isAlive == true

    companion object {
        const val THREAD_NAME = "aa-video"

        /** How long the read waits for a place in a full queue (see the class comment). */
        const val FULL_WAIT_MS = 300L
        private const val POLL_MS = 200L
        private const val JOIN_MS = 1_000L

        /**
         * How many frames the queue takes now. With the decoder up, [capacity]; with it still starting, [FrameQueue.WARMING_LIMIT]: a
         * decoder that takes 0.9 to 1.4 s to start (the older phones' OMX ones) used to meet a full queue, a frame dropped, a broken
         * chain and a keyframe asked for, in every session. The limit goes back to [capacity] only once the backlog is down to it, so
         * the frames that arrive while the decoder works it off are not dropped either.
         */
        internal fun limitFor(decoderReady: Boolean, waiting: Int, capacity: Int): Int =
            if (decoderReady && waiting <= capacity) capacity else FrameQueue.WARMING_LIMIT
    }
}
