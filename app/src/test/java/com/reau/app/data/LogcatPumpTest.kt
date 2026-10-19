package com.reau.app.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REau reads its own logcat to a file while the app lives, and opens the reader again if it dies. The pump is the part
 * without Android: a reader opened again and again, its lines handed on, until stopped.
 */
class LogcatPumpTest {
    @Test
    fun `every line of the reader is handed on, and a reader that ends is opened again`() {
        val lines = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(3)
        var round = 0
        val pump = LogcatPump(
            open = {
                opened.countDown()
                val r = round++
                sequenceOf("a$r", "b$r")
            },
            sink = { lines += it },
            pauseMs = 1,
        )
        pump.start()
        assertTrue("opened three times", opened.await(5, TimeUnit.SECONDS))
        pump.stop()
        assertTrue("the lines of the first two rounds, in order: $lines", lines.toList().take(4) == listOf("a0", "b0", "a1", "b1"))
    }

    @Test
    fun `a reader that cannot be opened is tried again, and the trouble is said once per try`() {
        val said = CopyOnWriteArrayList<String>()
        val tries = CountDownLatch(2)
        val pump = LogcatPump(
            open = { tries.countDown(); error("no logcat here") },
            sink = {},
            pauseMs = 1,
            log = { said += it },
        )
        pump.start()
        assertTrue(tries.await(5, TimeUnit.SECONDS))
        pump.stop()
        assertTrue("said why: $said", said.any { "no logcat here" in it })
    }

    @Test
    fun `after stop nothing is opened again`() {
        var opens = 0
        val pump = LogcatPump(open = { opens++; emptySequence() }, sink = {}, pauseMs = 50)
        pump.start()
        Thread.sleep(20)
        pump.stop()
        val after = opens
        Thread.sleep(200)
        assertEquals("no new reader after stop", after, opens)
    }
}
