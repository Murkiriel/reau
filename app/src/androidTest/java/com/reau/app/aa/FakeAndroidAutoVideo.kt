package com.reau.app.aa

import com.reau.app.aa.proto.Media
import com.reau.app.video.TestH264Source
import kotlin.concurrent.thread

/**
 * Test helper: makes a [FakeAndroidAutoPeer] send video the way Android Auto does once a session is up: start of
 * the video channel, the codec configuration, then pictures without end, [frameMs] apart. [units] is one encoded
 * sequence opening with a keyframe; it is sent over and over, so every round opens with a keyframe again.
 * Keyframes go in pieces, as a large picture does. [paused] stops the pictures without ending anything, as
 * Android Auto does while its screen does not change.
 */
class FakeAndroidAutoVideo(
    private val peer: FakeAndroidAutoPeer,
    private val units: List<TestH264Source.Unit>,
    private val frameMs: Long = 66,
) : AutoCloseable {
    @Volatile var paused = false

    /** Pictures sent so far. */
    @Volatile var sent = 0
        private set

    @Volatile private var running = true

    private val worker = thread(name = "fake-android-auto-video", isDaemon = true) {
        if (!peer.awaitHandshake(HANDSHAKE_WAIT_MS)) return@thread
        runCatching {
            peer.send(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(1).setConfigurationIndex(0).build().toByteArray())
            peer.send(Channel.ID_VID, type = 1, body = units.single { it.isConfig }.data)
            val frames = units.filter { !it.isConfig }
            var next = 0
            while (running) {
                if (!paused) {
                    send(frames[next % frames.size])
                    next++
                    sent++
                }
                Thread.sleep(frameMs)
            }
        }
    }

    private fun send(frame: TestH264Source.Unit) {
        val body = TIMESTAMP + frame.data
        if (body.size > PIECE_BYTES) {
            peer.sendInPieces(Channel.ID_VID, type = 0, body = body, pieces = body.size / PIECE_BYTES + 2)
        } else {
            peer.send(Channel.ID_VID, type = 0, body = body)
        }
    }

    override fun close() {
        running = false
        worker.join(2_000)
    }

    private companion object {
        const val HANDSHAKE_WAIT_MS = 30_000L
        const val PIECE_BYTES = 8_000
        val TIMESTAMP = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }
}
