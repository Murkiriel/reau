package com.reau.app.web

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real [Delayer]: runs a task after the time, never when cancelled, on a thread that does not hold the app up. */
class ExecutorDelayerTest {
    private val delayer = ExecutorDelayer()

    @After
    fun tearDown() = delayer.shutdown()

    @Test
    fun `a task runs after its time`() {
        val done = CountDownLatch(1)
        val started = System.nanoTime()
        delayer.after(100) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("not before 100 ms", System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(90))
    }

    @Test
    fun `a cancelled task never runs`() {
        val runs = AtomicInteger()
        val cancel = delayer.after(150) { runs.incrementAndGet() }
        cancel()
        Thread.sleep(400)
        assertEquals(0, runs.get())
    }

    @Test
    fun `after shutdown nothing runs and asking again is harmless`() {
        val runs = AtomicInteger()
        delayer.shutdown()
        delayer.after(10) { runs.incrementAndGet() }()
        Thread.sleep(200)
        assertEquals(0, runs.get())
    }
}
