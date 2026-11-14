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
    private var failed = 0
    private var pipe: AaVideoPipe? = null

    @After
    fun tearDown() {
        pipe?.stop()
    }

    private fun open(
        ready: () -> Boolean = { true },
        decode: (ByteArray, Int, Int) -> Unit = { b, o, s -> decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList() },
    ) =
        AaVideoPipe(decode = decode, onDropped = { dropped++ }, onFailed = { failed++ }, decoderReady = ready).also {
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

    @Test
    fun `a decoder that throws on a frame says the frame is lost, once, and the feed goes on`() {
        var calls = 0
        val p = open { b, o, s ->
            calls++
            if (calls == 1) error("codec gone")
            decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList()
        }
        p.put(byteArrayOf(1), 0, 1)
        waitFor("the failure said") { failed == 1 }
        p.put(byteArrayOf(2), 0, 1)
        waitFor("the second frame") { decoded.size == 1 }
        assertEquals("said once, for the one frame", 1, failed)
        assertEquals("it is a failure, not a full queue", 0, dropped)
    }

    @Test
    fun `with the decoder still starting, the queue takes forty frames and drops none`() {
        val gate = CountDownLatch(1)
        val inDecoder = CountDownLatch(1)
        val p = open(ready = { false }) { b, o, s ->
            inDecoder.countDown()
            gate.await(5, TimeUnit.SECONDS)
            decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList()
        }
        p.put(byteArrayOf(0), 0, 1)
        assertTrue(inDecoder.await(5, TimeUnit.SECONDS)) // the decoder is inside its start
        for (i in 1..40) p.put(byteArrayOf(i.toByte()), 0, 1)
        assertEquals("nothing dropped while the decoder starts", 0, dropped)
        gate.countDown()
        waitFor("forty-one frames") { decoded.size == 41 }
        assertEquals((0..40).map { listOf(it.toByte()) }, decoded.map { it.second })
    }

    @Test
    fun `with the decoder up and a backlog from its start, frames that arrive are not dropped either`() {
        val gate = CountDownLatch(1)
        val inDecoder = CountDownLatch(1)
        var ready = false
        val p = open(ready = { ready }) { b, o, s ->
            inDecoder.countDown()
            gate.await(5, TimeUnit.SECONDS)
            decoded += Thread.currentThread().name to b.copyOfRange(o, o + s).toList()
        }
        p.put(byteArrayOf(0), 0, 1)
        assertTrue(inDecoder.await(5, TimeUnit.SECONDS))
        for (i in 1..10) p.put(byteArrayOf(i.toByte()), 0, 1)
        ready = true // the decoder is up; ten frames still wait, more than its four places
        p.put(byteArrayOf(11), 0, 1)
        assertEquals("the backlog does not make the next frame a drop", 0, dropped)
        gate.countDown()
        waitFor("twelve frames") { decoded.size == 12 }
    }

    @Test
    fun `with the decoder up and the queue full, the fifth waiting frame is dropped as before`() {
        val gate = CountDownLatch(1)
        val inDecoder = CountDownLatch(1)
        val p = open(ready = { true }) { _, _, _ ->
            inDecoder.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }
        p.put(byteArrayOf(0), 0, 1)
        assertTrue(inDecoder.await(5, TimeUnit.SECONDS))
        for (i in 1..4) p.put(byteArrayOf(i.toByte()), 0, 1)
        p.put(byteArrayOf(5), 0, 1)
        assertEquals(1, dropped)
        gate.countDown()
    }

    @Test
    fun `the limit is the capacity only with the decoder up and the backlog worked off`() {
        val c = FrameQueue.CAPACITY
        assertEquals(c, AaVideoPipe.limitFor(decoderReady = true, waiting = 0, capacity = c))
        assertEquals(c, AaVideoPipe.limitFor(decoderReady = true, waiting = c, capacity = c))
        assertEquals(FrameQueue.WARMING_LIMIT, AaVideoPipe.limitFor(decoderReady = true, waiting = c + 1, capacity = c))
        assertEquals(FrameQueue.WARMING_LIMIT, AaVideoPipe.limitFor(decoderReady = false, waiting = 0, capacity = c))
    }
}
