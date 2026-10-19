package com.reau.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video's life with the viewers: up with the first, down 10 s after the last, a joiner caught up. */
class WebStreamTest {
    private class FakeSource(var startsOk: Boolean = true) : WebVideoSource {
        var starts = 0
        var stops = 0
        var keyRequests = 0
        override fun start(): Boolean { starts++; return startsOk }
        override fun stop() { stops++ }
        override fun requestKeyFrame() { keyRequests++ }
    }

    /** A clock the test turns by hand: what is [after]ed runs when [advance] passes its time, unless cancelled. */
    private class FakeDelayer : Delayer {
        private class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L
        private val jobs = mutableListOf<Job>()
        val pending: Int get() = jobs.count { !it.cancelled }

        override fun after(ms: Long, task: () -> Unit): () -> Unit {
            val job = Job(now + ms, task)
            jobs += job
            return { job.cancelled = true }
        }

        /** Moves the clock by [ms], running each job in the order of its time, the ones that jobs ask for while running included. */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                now = next.at
                next.cancelled = true
                next.task()
            }
            now = target
        }
    }

    private val source = FakeSource()
    private val delayer = FakeDelayer()
    private val published = mutableListOf<Int>()
    private var viewers = 0
    private val stream = WebStream(source, { published += it.last().toInt() }, delayer, { viewers })

    private fun frame(id: Int) = byteArrayOf(0, 0, 0, 1, id.toByte())

    @Test
    fun `a viewer that arrives after the shutdown does not bring the source back`() {
        stream.shutdown()
        viewers++
        stream.viewerJoined { }
        assertEquals(0, source.starts)
        assertFalse(stream.running)
    }

    private fun join(): MutableList<Int> {
        val got = mutableListOf<Int>()
        viewers++
        stream.viewerJoined { got += it.last().toInt() }
        return got
    }

    private fun leave() {
        viewers--
        stream.viewersChanged(viewers)
    }

    @Test
    fun `the source starts with the first viewer and only once`() {
        join()
        join()
        assertEquals(1, source.starts)
    }

    @Test
    fun `the source stops 10 seconds after the last viewer leaves, not before`() {
        join()
        leave()
        delayer.advance(9_999)
        assertEquals(0, source.stops)
        delayer.advance(1)
        assertEquals(1, source.stops)
    }

    @Test
    fun `a viewer who comes back before the 10 seconds keeps the source running`() {
        join()
        leave()
        delayer.advance(5_000)
        join()
        delayer.advance(20_000)
        assertEquals(0, source.stops)
        assertEquals(1, source.starts)
    }

    @Test
    fun `one viewer leaving while another stays stops nothing`() {
        join()
        join()
        leave()
        delayer.advance(60_000)
        assertEquals(0, source.stops)
    }

    @Test
    fun `after a stop the next viewer starts it again`() {
        join()
        leave()
        delayer.advance(10_000)
        join()
        assertEquals(2, source.starts)
        assertEquals(1, source.stops)
    }

    @Test
    fun `the timer does not stop a source a viewer is still using`() {
        join()
        leave()
        viewers++ // came in without the stream hearing yet
        delayer.advance(10_000)
        assertEquals(0, source.stops)
    }

    @Test
    fun `a source that does not start is tried again for the next viewer`() {
        source.startsOk = false
        join()
        source.startsOk = true
        join()
        assertEquals(2, source.starts)
        assertEquals(0, source.stops)
    }

    @Test
    fun `a joiner gets the cache then a key frame is asked for`() {
        join()
        stream.output(frame(100), key = false, config = true)
        stream.output(frame(1), key = true, config = false)
        stream.output(frame(2), key = false, config = false)
        val late = join()
        assertEquals(listOf(100, 1, 2), late)
        assertEquals(2, source.keyRequests)
    }

    @Test
    fun `every output is published, config and frames, in order`() {
        join()
        stream.output(frame(100), key = false, config = true)
        stream.output(frame(1), key = true, config = false)
        stream.output(frame(2), key = false, config = false)
        assertEquals(listOf(100, 1, 2), published)
    }

    @Test
    fun `a buffer with an IDR counts as a key frame even if the encoder did not flag it`() {
        join()
        stream.output(frame(100), key = false, config = true)
        stream.output(byteArrayOf(0, 0, 0, 1, 0x65, 7), key = false, config = false)
        stream.output(frame(9), key = false, config = false)
        val late = join()
        assertEquals(listOf(100, 7, 9), late)
    }

    @Test
    fun `after the source stops the cache is empty, so no old picture reaches a new viewer`() {
        join()
        stream.output(frame(100), key = false, config = true)
        stream.output(frame(1), key = true, config = false)
        leave()
        delayer.advance(10_000)
        val later = join()
        assertTrue(later.toString(), later.isEmpty())
    }

    @Test
    fun `while it runs, a key frame is asked of the encoder every second, so a viewer that lost the thread finds one soon`() {
        join()
        assertEquals(1, source.keyRequests) // the one of the joiner
        delayer.advance(999)
        assertEquals(1, source.keyRequests)
        delayer.advance(1)
        assertEquals(2, source.keyRequests)
        delayer.advance(3_000)
        assertEquals(5, source.keyRequests)
    }

    @Test
    fun `the key frame pulse stops with the source, and starts again with the next viewer`() {
        join()
        leave()
        delayer.advance(10_000)
        val after = source.keyRequests
        delayer.advance(5_000)
        assertEquals("no pulse after the stop", after, source.keyRequests)
        join()
        delayer.advance(1_000)
        assertEquals(after + 2, source.keyRequests)
    }

    @Test
    fun `a source that did not start has no pulse`() {
        source.startsOk = false
        join()
        delayer.advance(5_000)
        assertEquals(0, source.keyRequests)
    }

    @Test
    fun `shutdown stops the source at once and cancels the timer`() {
        join()
        leave()
        stream.shutdown()
        assertEquals(1, source.stops)
        assertEquals(0, delayer.pending)
        delayer.advance(60_000)
        assertEquals(1, source.stops)
        assertFalse(stream.running)
    }
}
