package com.reau.app.aa

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The frames between Android Auto's read thread, which [offer]s them, and the thread that
 * feeds the decoder, which [take]s them and gives each back with [recycle]. At most [limit] wait ([capacity] to begin
 * with): past that the offer is refused and the caller drops the frame. The limit moves: the pipe raises it to
 * [WARMING_LIMIT] while the decoder starts up and lowers it again once the decoder runs. Each frame is copied in (the
 * read thread reuses its buffer for the next message) into a buffer from a small pool, grown when a frame does not
 * fit, instead of a new array per frame. An offer may wait a while for a place (the decoder taking its time to
 * start) before refusing.
 */
internal class FrameQueue(val capacity: Int = CAPACITY) {
    /** One frame: its bytes are `data[0 until size]`; [data] is a pool buffer, larger than the frame. */
    class Frame(var data: ByteArray, var size: Int)

    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    private val notEmpty = lock.newCondition()
    private val frames = ArrayDeque<Frame>()
    private val pool = ConcurrentLinkedQueue<Frame>()

    /**
     * How many frames may wait. Lowering it below what already waits drops none of them; it only keeps new ones out
     * until the queue is down to the limit. Raising it lets in the offers that were waiting for a place.
     */
    @Volatile
    var limit: Int = capacity
        set(value) {
            lock.withLock {
                field = value
                notFull.signalAll()
            }
        }

    val waiting: Int get() = lock.withLock { frames.size }

    /**
     * Copies `source[offset, offset + size)` into the queue; false (nothing kept) when [limit] frames still wait
     * after [waitMs] (or the waiting thread was interrupted).
     */
    fun offer(source: ByteArray, offset: Int, size: Int, waitMs: Long = 0): Boolean {
        if (waitMs <= 0 && waiting >= limit) return false
        val frame = pool.poll() ?: Frame(ByteArray(size), 0)
        if (frame.data.size < size) frame.data = ByteArray(size)
        System.arraycopy(source, offset, frame.data, 0, size)
        frame.size = size
        val queued = try {
            put(frame, TimeUnit.MILLISECONDS.toNanos(waitMs))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!queued) recycle(frame)
        return queued
    }

    private fun put(frame: Frame, waitNanos: Long): Boolean = lock.withLock {
        var left = waitNanos
        while (frames.size >= limit) {
            if (left <= 0) return false
            left = notFull.awaitNanos(left)
        }
        frames.addLast(frame)
        notEmpty.signal()
        true
    }

    /** The oldest frame, waiting up to [timeoutMs] for one; null if none came. */
    fun take(timeoutMs: Long): Frame? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (frames.isEmpty()) {
            if (left <= 0) return null
            left = notEmpty.awaitNanos(left)
        }
        frames.removeFirst().also { notFull.signal() }
    }

    /** A frame [take]n and done with: its buffer goes back to the pool (at most one per place in the queue, plus one). */
    fun recycle(frame: Frame) {
        if (pool.size <= limit) pool.offer(frame)
    }

    /** The waiting frames given back, unread. */
    fun clear() {
        val gone = lock.withLock {
            frames.toList().also {
                frames.clear()
                notFull.signalAll()
            }
        }
        gone.forEach(::recycle)
    }

    companion object {
        /** About 130 ms of Android Auto's 30 fps. */
        const val CAPACITY = 4

        /** About 2 s of Android Auto's 30 fps: what may wait while the decoder starts up (the slowest took 1.4 s). */
        const val WARMING_LIMIT = 60
    }
}
