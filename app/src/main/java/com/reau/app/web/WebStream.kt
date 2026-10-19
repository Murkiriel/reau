package com.reau.app.web

/** Where the video comes from: an encoder fed by the compositor. Started with the first viewer, stopped after the last. */
interface WebVideoSource {
    /** Brings the encoder up and points the compositor at it; false if it could not. */
    fun start(): Boolean

    /** Lets go of the compositor's output and the encoder. */
    fun stop()

    /** Asks the encoder (never Android Auto) for a key frame soon. */
    fun requestKeyFrame()
}

/** Runs [task] after [ms]; the function it returns cancels it. */
fun interface Delayer {
    fun after(ms: Long, task: () -> Unit): () -> Unit
}

/**
 * The video's life with the viewers: the source starts with the first viewer, stops [idleMs] after the
 * last one left (and only if nobody came back), and each viewer who joins is caught up from the [WebFrameCache] and a key
 * frame is asked of the encoder. [output] takes what the encoder gives and [publish]es it to everyone.
 *
 * While it runs, a key frame is asked of the encoder every [PULSE_MS]: the encoder's own interval counts frames, not seconds, and
 * with a still picture few frames are made, so a viewer whose decoder lost the thread would wait long for the next one.
 *
 * Two locks: [control] orders the starts, stops and joins; [data] guards the cache and keeps the order of what is cached
 * and published (the encoder's thread takes only this one, so a stop that waits for that thread never waits for itself: nothing
 * the publishing calls back into takes [control] on that thread, see `WebViewers`).
 */
class WebStream(
    private val source: WebVideoSource,
    private val publish: (ByteArray) -> Unit,
    private val delayer: Delayer,
    private val viewerCount: () -> Int,
    private val log: (String) -> Unit = {},
    private val idleMs: Long = IDLE_MS,
) {
    private val control = Any()
    private val data = Any()
    private val cache = WebFrameCache()
    private var cancelStop: (() -> Unit)? = null
    private var cancelPulse: (() -> Unit)? = null
    private var closed = false

    @Volatile var running = false
        private set

    /** A viewer joined ([send] queues a message on it): the source runs, the viewer is caught up, a key frame is asked. */
    fun viewerJoined(send: (ByteArray) -> Unit) = synchronized(control) {
        if (closed) return@synchronized // a viewer that came in while the server was going down must not bring the encoder back
        cancelStop?.invoke()
        cancelStop = null
        if (!running) {
            running = source.start()
            if (running) schedulePulse() else log("web: video source did not start")
        }
        synchronized(data) { cache.snapshot().forEach(send) }
        if (running) source.requestKeyFrame()
    }

    /** The viewer count changed: at none, the source stops after [idleMs]. */
    fun viewersChanged(count: Int) = synchronized(control) {
        if (count == 0 && running && cancelStop == null) cancelStop = delayer.after(idleMs) { stopIfIdle() }
    }

    /** What the encoder gave: the parameters (config) or a frame; cached, then sent to every viewer. */
    fun output(bytes: ByteArray, key: Boolean, config: Boolean) = synchronized(data) {
        if (config) cache.setConfig(bytes) else cache.addFrame(bytes, key || AnnexB.hasIdr(bytes))
        publish(bytes)
    }

    /** Stops the source now (the projection or the server ended). */
    fun shutdown() = synchronized(control) {
        closed = true
        cancelStop?.invoke()
        cancelStop = null
        stopNow()
    }

    private fun stopIfIdle() = synchronized(control) {
        cancelStop = null
        if (viewerCount() == 0) stopNow()
    }

    private fun schedulePulse() {
        cancelPulse = delayer.after(PULSE_MS) { pulse() }
    }

    private fun pulse() = synchronized(control) {
        cancelPulse = null
        if (running) {
            source.requestKeyFrame()
            schedulePulse()
        }
    }

    private fun stopNow() {
        if (!running) return
        running = false
        cancelPulse?.invoke()
        cancelPulse = null
        source.stop()
        synchronized(data) { cache.clear() }
        log("web: video source stopped")
    }

    private companion object {
        const val IDLE_MS = 10_000L
        const val PULSE_MS = 1_000L
    }
}
