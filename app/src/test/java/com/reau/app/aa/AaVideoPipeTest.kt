package com.reau.app.aa

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder fed on its own thread (`aa-video`) from [FrameQueue], so
 * Android Auto's read thread only copies the frame in and goes back to reading. A full queue drops the frame and says
 * so once per frame; stopping ends the thread without waiting on the decoder.
 */
class AaVideoPipeTest {
    private val decoded = Collections.synchronizedList(mutableListOf<Pair<String, List<Byte>>>())
    private var dropped = 0
    private var pipe: AaVideoPipe? = null

    @After
    fun tearDown() {
        pipe?.stop()
    }

    private fun open(decode: (ByteArray, Int, Int) -> Unit = { b, o, s -> decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList() }) =
        AaVideoPipe(decode = decode, onDropped = { dropped++ }).also {
            pipe = it
            it.start()
        }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    @Test
    fun `frames reach the decoder in order, on its own thread, from a copy`() {
        val p = open()
        val buffer = byteArrayOf(1, 2, 3)
        p.put(buffer, 0, 3)
        buffer.fill(9) // the read thread reuses its buffer at once
        p.put(byteArrayOf(4, 5), 0, 2)
        waitFor("two frames") { decoded.size == 2 }
        assertEquals(listOf<Byte>(1, 2, 3), decoded[0].second)
        assertEquals(listOf<Byte>(4, 5), decoded[1].second)
        assertTrue("not the caller's thread", decoded.all { it.first == AaVideoPipe.THREAD_NAME })
    }

    @Test
    fun `with the decoder held, four frames wait and the next is dropped and said`() {
        val gate = CountDownLatch(1)
        val inDecoder = CountDownLatch(1)
        val p = open { b, o, s ->
            inDecoder.countDown()
            gate.await(5, TimeUnit.SECONDS)
            decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList()
        }
        p.put(byteArrayOf(0), 0, 1)
        assertTrue(inDecoder.await(5, TimeUnit.SECONDS)) // the first one is inside the decoder
        for (i in 1..4) p.put(byteArrayOf(i.toByte()), 0, 1)
        assertEquals("nothing dropped while there is room", 0, dropped)
        p.put(byteArrayOf(5), 0, 1)
        assertEquals("the fifth waiting one is dropped", 1, dropped)
        gate.countDown()
        waitFor("five frames") { decoded.size == 5 }
        assertEquals((0..4).map { listOf(it.toByte()) }, decoded.map { it.second })
    }

    @Test
    fun `a decoder held less than the wait, as one starting up, loses nothing`() {
        val gate = CountDownLatch(1)
        val inDecoder = CountDownLatch(1)
        val p = open { b, o, s ->
            inDecoder.countDown()
            gate.await(5, TimeUnit.SECONDS)
            decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList()
        }
        p.put(byteArrayOf(0), 0, 1)
        assertTrue(inDecoder.await(5, TimeUnit.SECONDS))
        for (i in 1..4) p.put(byteArrayOf(i.toByte()), 0, 1)
        Thread {
            Thread.sleep(AaVideoPipe.FULL_WAIT_MS / 3)
            gate.countDown()
        }.start()
        p.put(byteArrayOf(5), 0, 1) // waits for the place the decoder frees
        waitFor("six frames") { decoded.size == 6 }
        assertEquals("none dropped", 0, dropped)
        assertEquals((0..5).map { listOf(it.toByte()) }, decoded.map { it.second })
    }

    @Test
    fun `stop ends the thread and nothing more is decoded`() {
        val p = open()
        p.put(byteArrayOf(1), 0, 1)
        waitFor("one frame") { decoded.size == 1 }
        p.stop()
        assertFalse("the thread ended", p.testOnlyThreadAlive())
        // More than the queue holds: a stopped pipe takes none in, so none can fill it and be said dropped (a keyframe
        // asked for in a session already over).
        repeat(FrameQueue.CAPACITY + 2) { p.put(byteArrayOf(2), 0, 1) }
        Thread.sleep(50)
        assertEquals("nothing after stop", 1, decoded.size)
        assertEquals("and not counted as dropped", 0, dropped)
    }

    @Test
    fun `a decoder that throws does not end the thread`() {
        var calls = 0
        val p = open { b, o, s ->
            calls++
            if (calls == 1) error("codec gone")
            decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList()
        }
        p.put(byteArrayOf(1), 0, 1)
        p.put(byteArrayOf(2), 0, 1)
        waitFor("the second frame") { decoded.size == 1 }
        assertEquals(listOf<Byte>(2), decoded[0].second)
    }
}
