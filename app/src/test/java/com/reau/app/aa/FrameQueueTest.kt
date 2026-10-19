package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule of the queue between
 * Android Auto's read thread and the decoder. At most 4 frames wait; a fifth is refused (the caller drops it, counts
 * it lost and asks for a keyframe). A frame is copied in, so the read thread may reuse its buffer at once, into
 * buffers that come back to a pool instead of a new array per frame.
 */
class FrameQueueTest {
    private fun frame(size: Int, value: Int) = ByteArray(size) { value.toByte() }

    @Test
    fun `four frames wait and a fifth is refused until one is taken`() {
        val q = FrameQueue()
        repeat(4) { assertTrue("frame $it waits", q.offer(frame(10, it), 0, 10)) }
        assertFalse("the fifth is refused", q.offer(frame(10, 9), 0, 10))
        assertEquals(4, q.waiting)
        q.recycle(checkNotNull(q.take(0)))
        assertTrue("room again", q.offer(frame(10, 9), 0, 10))
    }

    @Test
    fun `a full queue waits for a place up to the time given, and refuses only after it`() {
        val q = FrameQueue()
        repeat(4) { q.offer(frame(10, it), 0, 10) }
        val taker = Thread {
            Thread.sleep(100)
            q.recycle(checkNotNull(q.take(0)))
        }.apply { start() }
        val start = System.nanoTime()
        assertTrue("it got the place freed while it waited", q.offer(frame(10, 9), 0, 10, waitMs = 2_000))
        taker.join()
        assertTrue("it waited for it", (System.nanoTime() - start) / 1_000_000 >= 90)
        val refusedAt = System.nanoTime()
        assertFalse("nobody takes: refused", q.offer(frame(10, 8), 0, 10, waitMs = 150))
        assertTrue("after the time given", (System.nanoTime() - refusedAt) / 1_000_000 >= 140)
    }

    @Test
    fun `frames come out in order, each a copy of its part of the source`() {
        val q = FrameQueue()
        val source = byteArrayOf(9, 9, 1, 2, 3, 9)
        q.offer(source, 2, 3)
        source.fill(7) // the read thread reuses its buffer right away
        q.offer(frame(2, 5), 0, 2)
        val first = checkNotNull(q.take(0))
        assertArrayEquals(byteArrayOf(1, 2, 3), first.data.copyOf(first.size))
        val second = checkNotNull(q.take(0))
        assertArrayEquals(byteArrayOf(5, 5), second.data.copyOf(second.size))
        assertNull("nothing left", q.take(0))
    }

    @Test
    fun `a recycled buffer carries the next frame, and a larger frame gets a larger one`() {
        val q = FrameQueue()
        q.offer(frame(100, 1), 0, 100)
        val first = checkNotNull(q.take(0))
        val array = first.data
        q.recycle(first)
        q.offer(frame(80, 2), 0, 80)
        val second = checkNotNull(q.take(0))
        assertSame("the same buffer, not a new one", array, second.data)
        q.recycle(second)
        q.offer(frame(500, 3), 0, 500)
        val third = checkNotNull(q.take(0))
        assertNotSame("it did not fit", array, third.data)
        assertEquals(500, third.size)
    }

    @Test
    fun `clearing gives the waiting frames back and empties the queue`() {
        val q = FrameQueue()
        repeat(4) { q.offer(frame(10, it), 0, 10) }
        q.clear()
        assertEquals(0, q.waiting)
        assertNull(q.take(0))
        repeat(4) { assertTrue(q.offer(frame(10, it), 0, 10)) }
    }
}
