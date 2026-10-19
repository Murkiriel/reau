package com.reau.app.web

import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * The viewers of the web server: who is in, the place limit, the broadcast and the count told to the server's events.
 * A viewer is [reserve]d when its handshake begins (so the limit holds even for the ones still shaking hands) and is
 * [join]ed, and reached by the broadcast, only after the server's events had their chance to queue the first messages.
 */
internal class WebViewers(
    private val maxViewers: Int,
    private val queueFrames: Int,
    private val events: WebServer.Events,
) {
    private val lock = Any()
    private val viewers = ArrayList<WebViewer>()

    /**
     * Where the count goes to the server's events: one thread, in the order the counts were made, and never the thread that ended the viewer.
     * That thread can be the encoder's, which holds the stream's data lock; their answer takes the stream's other lock, and the
     * two together would deadlock. No thread waits here between the tellings (zero core threads).
     */
    private val tellings = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 2, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "web-viewers-told").apply { isDaemon = true } }

    private fun tell(count: Int) {
        runCatching { tellings.execute { events.onViewers(count) } }
    }

    /** The viewers in, as the server's events were told. */
    val liveCount: Int get() = synchronized(lock) { viewers.count { it.live } }

    /** A place for one more viewer on [socket], or null when they are all taken. */
    fun reserve(socket: Socket, soundOff: Boolean = false): WebViewer? = synchronized(lock) {
        if (viewers.size >= maxViewers) return@synchronized null
        WebViewer(socket, queueFrames, ::gone).also {
            it.soundOff = soundOff
            viewers += it
        }
    }

    fun join(viewer: WebViewer) {
        val count = synchronized(lock) {
            viewer.live = true
            viewers.count { it.live }.also(::tell)
        }
        if (viewer.soundOff) viewer.sendText(WebProtocol.SOUND_OFF)
        events.log("web: viewer joined ($count)")
    }

    /**
     * One encoded frame to every viewer in; a viewer too slow for it drops itself, unless the frame is [droppable] (sound). Sound does not
     * go to a viewer that is on the phone itself ([WebViewer.soundOff]).
     */
    fun broadcast(frame: ByteArray, droppable: Boolean = false) =
        snapshot(liveOnly = true).filter { !droppable || !it.soundOff }.forEach { it.enqueue(frame, droppable) }

    fun closeAll() = snapshot(liveOnly = false).forEach { it.close() }

    private fun snapshot(liveOnly: Boolean): List<WebViewer> =
        synchronized(lock) { if (liveOnly) viewers.filter { it.live } else viewers.toList() }

    private fun gone(viewer: WebViewer) {
        val (wasLive, count) = synchronized(lock) {
            viewers -= viewer
            val count = viewers.count { it.live }
            if (viewer.live) tell(count)
            viewer.live to count
        }
        if (!wasLive) return
        events.log("web: viewer left ($count)")
    }

    /** Reads the viewer's messages until it leaves: text goes to the events, a ping is answered, a close or a fault ends it. */
    fun listen(viewer: WebViewer, input: InputStream) {
        while (true) {
            val frame = try {
                WebSocketCodec.read(input, MAX_CLIENT_PAYLOAD)
            } catch (_: WebSocketViolation) {
                null
            } catch (_: IOException) {
                null
            } ?: return
            when (frame.opcode) {
                WebSocketCodec.TEXT -> events.onText(String(frame.payload, Charsets.UTF_8))
                WebSocketCodec.PING -> viewer.enqueue(WebSocketCodec.encode(WebSocketCodec.PONG, frame.payload))
                WebSocketCodec.CLOSE -> return
            }
        }
    }

    private companion object {
        const val MAX_CLIENT_PAYLOAD = 4096
    }
}
