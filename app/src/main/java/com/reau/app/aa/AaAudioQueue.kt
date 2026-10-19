package com.reau.app.aa

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The queue between Android Auto's read thread and the phone's speaker. [offer] never waits: with
 * more than [maxBytes] waiting, the oldest chunks are dropped (and counted) and the newest kept, so the sound is late by at most
 * [maxBytes] and never grows behind; one chunk alone is kept whatever its size. [take] waits for a chunk up to its time.
 */
class AaAudioQueue(private val maxBytes: Int) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var bytes = 0

    /** Chunks dropped for being too old since the start. */
    @Volatile var dropped = 0
        private set

    fun offer(chunk: ByteArray) = lock.withLock {
        chunks.addLast(chunk)
        bytes += chunk.size
        while (bytes > maxBytes && chunks.size > 1) {
            bytes -= chunks.removeFirst().size
            dropped++
        }
        arrived.signal()
    }

    /** The oldest chunk, waiting up to [timeoutMs] for one; null if none came. */
    fun take(timeoutMs: Long): ByteArray? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (chunks.isEmpty() && left > 0) left = arrived.awaitNanos(left)
        chunks.removeFirstOrNull()?.also { bytes -= it.size }
    }

    fun clear() = lock.withLock {
        chunks.clear()
        bytes = 0
    }

    companion object {
        /** A fifth of a second of [stream]: what may wait before the oldest is dropped. */
        fun maxBytesFor(stream: AaAudioFeed.Stream): Int = stream.sampleRate * stream.frameBytes / 5
    }
}
