package com.reau.app.aa

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The frames between Android Auto's read thread, which [offer]s them, and the thread that
 * feeds the decoder, which [take]s them and gives each back with [recycle]. At most [capacity] wait: past that the
 * offer is refused and the caller drops the frame. Each frame is copied in (the read thread reuses its buffer for the
 * next message) into a buffer from a small pool, grown when a frame does not fit, instead of a new array per frame.
 * An offer may wait a while for a place (the decoder taking its time to start) before refusing.
 */
internal class FrameQueue(private val capacity: Int = CAPACITY) {
    /** One frame: its bytes are `data[0 until size]`; [data] is a pool buffer, larger than the frame. */
    class Frame(var data: ByteArray, var size: Int)

    private val frames = ArrayBlockingQueue<Frame>(capacity)
    private val pool = ConcurrentLinkedQueue<Frame>()

    val waiting: Int get() = frames.size

    /**
     * Copies `source[offset, offset + size)` into the queue; false (nothing kept) when [capacity] frames still wait
     * after [waitMs] (or the waiting thread was interrupted).
     */
    fun offer(source: ByteArray, offset: Int, size: Int, waitMs: Long = 0): Boolean {
        if (waitMs <= 0 && frames.remainingCapacity() == 0) return false
        val frame = pool.poll() ?: Frame(ByteArray(size), 0)
        if (frame.data.size < size) frame.data = ByteArray(size)
        System.arraycopy(source, offset, frame.data, 0, size)
        frame.size = size
        val queued = try {
            frames.offer(frame, waitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!queued) recycle(frame)
        return queued
    }

    /** The oldest frame, waiting up to [timeoutMs] for one; null if none came. */
    fun take(timeoutMs: Long): Frame? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** A frame [take]n and done with: its buffer goes back to the pool (at most one per place in the queue, plus one). */
    fun recycle(frame: Frame) {
        if (pool.size <= capacity) pool.offer(frame)
    }

    /** The waiting frames given back, unread. */
    fun clear() {
        while (true) recycle(frames.poll() ?: return)
    }

    companion object {
        /** About 130 ms of Android Auto's 30 fps. */
        const val CAPACITY = 4
    }
}
