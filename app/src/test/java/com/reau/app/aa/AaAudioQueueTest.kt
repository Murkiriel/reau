package com.reau.app.aa

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue between Android Auto's read thread and the phone's speaker: the read thread never waits
 * for the speaker, so when the speaker falls behind the oldest sound is dropped, never the newest, and what is kept stays in order.
 */
class AaAudioQueueTest {
    private fun chunk(id: Int, size: Int = 10) = ByteArray(size) { id.toByte() }

    @Test
    fun `what comes in goes out in the same order`() {
        val q = AaAudioQueue(maxBytes = 100)
        q.offer(chunk(1))
        q.offer(chunk(2))
        assertArrayEquals(chunk(1), q.take(10))
        assertArrayEquals(chunk(2), q.take(10))
    }

    @Test
    fun `nothing waiting is null after the time, not for ever`() {
        val q = AaAudioQueue(maxBytes = 100)
        val started = System.nanoTime()
        assertNull(q.take(50))
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(40))
    }

    @Test
    fun `past the limit the oldest is dropped and the newest kept`() {
        val q = AaAudioQueue(maxBytes = 30)
        for (id in 1..5) q.offer(chunk(id)) // 50 bytes asked, 30 kept
        assertEquals(2, q.dropped)
        assertEquals(3, q.take(10)!![0].toInt())
        assertEquals(4, q.take(10)!![0].toInt())
        assertEquals(5, q.take(10)!![0].toInt())
        assertNull(q.take(10))
    }

    @Test
    fun `one chunk bigger than the limit is still kept, since dropping it would leave nothing`() {
        val q = AaAudioQueue(maxBytes = 5)
        q.offer(chunk(7, size = 20))
        assertEquals(0, q.dropped)
        assertEquals(20, q.take(10)!!.size)
    }

    @Test
    fun `a taker that waits gets what comes later`() {
        val q = AaAudioQueue(maxBytes = 100)
        val got = arrayOfNulls<ByteArray>(1)
        val done = CountDownLatch(1)
        Thread { got[0] = q.take(5_000); done.countDown() }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        q.offer(chunk(9))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertArrayEquals(chunk(9), got[0])
    }

    @Test
    fun `clear empties it`() {
        val q = AaAudioQueue(maxBytes = 100)
        q.offer(chunk(1))
        q.clear()
        assertNull(q.take(10))
    }

    @Test
    fun `the limit of the phone's playback is a fifth of a second of the stream`() {
        assertEquals(48_000 * 4 / 5, AaAudioQueue.maxBytesFor(AaAudioFeed.Stream.MEDIA))
        assertEquals(16_000 * 2 / 5, AaAudioQueue.maxBytesFor(AaAudioFeed.Stream.GUIDANCE))
    }
}
