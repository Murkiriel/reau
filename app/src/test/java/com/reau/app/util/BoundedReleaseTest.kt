package com.reau.app.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Releasing a codec whose driver hangs must cost the caller a bounded wait, never the thread. */
class BoundedReleaseTest {
    private val stuck = AtomicInteger()

    @Test
    fun `a task that finishes in time is true and nothing is said`() {
        var ran = false
        val ok = BoundedRelease.run({ ran = true }, joinMs = 2_000L, onStuck = { stuck.incrementAndGet() })
        assertTrue(ok)
        assertTrue(ran)
        assertEquals(0, stuck.get())
    }

    @Test
    fun `a task that never comes back is given up on after the time, said once, and left to its thread`() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val start = System.nanoTime()
        val ok = BoundedRelease.run(
            task = {
                release.await(10, TimeUnit.SECONDS)
                finished.countDown()
            },
            joinMs = 300L,
            onStuck = { stuck.incrementAndGet() },
        )
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertFalse(ok)
        assertEquals(1, stuck.get())
        assertTrue("it waited the time given: $tookMs ms", tookMs >= 290)
        assertTrue("and no longer: $tookMs ms", tookMs < 3_000)
        assertEquals("the task has not finished: it runs on its own thread", 1L, finished.count)
        release.countDown()
        assertTrue("it ends when the driver lets it", finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a task that throws is false, without onStuck and without the exception reaching the caller`() {
        val ok = BoundedRelease.run({ error("driver gone") }, joinMs = 2_000L, onStuck = { stuck.incrementAndGet() })
        assertFalse(ok)
        assertEquals(0, stuck.get())
    }

    @Test
    fun `the task runs on a daemon thread of its own`() {
        var name = ""
        var daemon = false
        BoundedRelease.run({
            name = Thread.currentThread().name
            daemon = Thread.currentThread().isDaemon
        }, joinMs = 2_000L, onStuck = {})
        assertEquals(BoundedRelease.THREAD_NAME, name)
        assertTrue(daemon)
        assertFalse(name == Thread.currentThread().name)
    }

    @Test
    fun `an interrupt on the caller does not cut the wait short and is given back`() {
        Thread.currentThread().interrupt()
        try {
            var done = false
            val ok = BoundedRelease.run({
                Thread.sleep(150)
                done = true
            }, joinMs = 2_000L, onStuck = { stuck.incrementAndGet() })
            assertTrue("the task was waited for", ok && done)
            assertEquals(0, stuck.get())
            assertTrue("the interrupt is still there", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
