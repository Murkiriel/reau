package com.reau.app.web

import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One browser on the WebSocket. What is sent to it waits in a bounded queue and goes out on a thread of its own,
 * so a slow browser never holds back the others nor the encoder: when its queue is full it is dropped, not waited for.
 * The server's [WebServer.Events] gets one in `onJoin` to queue what a new viewer must see first.
 */
class WebViewer internal constructor(
    private val socket: Socket,
    queueFrames: Int,
    private val onGone: (WebViewer) -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>(queueFrames)
    private val soundLimit = maxOf(1, queueFrames / 2)
    private val closed = AtomicBoolean(false)

    /** Set by the server once [WebServer.Events] knows of the viewer: only then does the broadcast reach it. */
    @Volatile internal var live = false

    /** True for a browser on the phone itself: it gets no sound ([LocalNetwork.isThePhone]). */
    @Volatile internal var soundOff = false

    /** Queues a binary message; false if the viewer is gone or too slow (and was dropped by this call). */
    fun sendBinary(bytes: ByteArray): Boolean = enqueue(WebSocketCodec.encode(WebSocketCodec.BINARY, bytes))

    /** Queues a text message; same answer as [sendBinary]. */
    fun sendText(text: String): Boolean = enqueue(WebSocketCodec.encode(WebSocketCodec.TEXT, text.toByteArray()))

    /**
     * Queues a frame already encoded (the broadcast encodes once for all). A full queue drops the viewer, except for [droppable] frames
     * (the sound): with the queue half full they are let go instead, so a stall of the network costs a bit of sound, not the viewer.
     */
    internal fun enqueue(frame: ByteArray, droppable: Boolean = false): Boolean = when {
        closed.get() -> false
        droppable && queue.size >= soundLimit -> true
        else -> queue.offer(frame) || fail()
    }

    private fun fail(): Boolean {
        close()
        return false
    }

    internal fun startWriter() {
        Thread(::write, "web-viewer-writer").apply { isDaemon = true }.start()
    }

    private fun write() {
        try {
            // Inside the try: a browser that closed the connection before this thread began leaves a socket with no output stream.
            val out = socket.getOutputStream()
            while (!closed.get()) {
                val frame = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                out.write(frame)
                out.flush()
            }
        } catch (_: IOException) {
            close()
        } catch (_: InterruptedException) {
            close()
        }
    }

    /** Ends the viewer: closes its socket (which ends the reading too) and tells the server once. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            onGone(this)
        }
    }

    private companion object {
        const val POLL_MS = 200L
    }
}
