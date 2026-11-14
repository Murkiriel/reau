package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One drop, one notice. fail() closes the socket and only then writes ERROR; closing the socket wakes the receive loop with "Socket closed", and
 * that loop calls fail() too. On a slow machine (CI, 11/11/2026) the loop got there before ERROR was written and the rider was told twice.
 *
 * No timing here: the readings' sink is called by fail() between closing the socket and writing ERROR, which is exactly the gap, and the sink of
 * this test makes the second cause arrive right there, from another thread, and waits for it to be done with.
 */
class DashSessionOneFailureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reported = CopyOnWriteArrayList<String>()
    private val gate = CountDownLatch(1)
    private var session: DashSession? = null

    @After
    fun tearDown() {
        session?.disconnect()
        gate.countDown()
        scope.cancel()
    }

    /** A session whose teardown, the first time, is met halfway by [second] from another thread. Its connect() waits at [gate]. */
    private fun sessionMetHalfway(second: String): DashSession {
        val flushes = AtomicInteger()
        lateinit var s: DashSession
        val sink = object : DashReadingSink {
            override fun onValue(type: Int, sub: Int, value: ByteArray) = Unit
            override fun flush() {
                if (flushes.incrementAndGet() != 1) return
                val other = thread(name = "second-cause") { s.fail(second) }
                other.join(JOIN_MS)
                assertFalse("the second fail() never returned", other.isAlive)
            }
        }
        s = DashSession(scope, socketFactory = { gate.await(); throw java.net.BindException("held closed by the test") }, readings = sink)
        s.onError = { why -> reported += why }
        session = s
        return s
    }

    @Test
    fun `a second cause that arrives while the first is tearing the session down is not told to the rider`() {
        val s = sessionMetHalfway("Lost connection to dash — socket error: Socket closed")

        s.fail("Dash stopped responding — connection lost")

        assertEquals("one notice, the first cause", listOf("Dash stopped responding — connection lost"), reported.toList())
        assertEquals(DashState.ERROR, s.state.value)
    }

    @Test
    fun `the next session's failure is told again`() {
        val s = sessionMetHalfway("Lost connection to dash — socket error: Socket closed")
        s.fail("Dash stopped responding — connection lost")

        s.connect("RE_TEST_1234")
        assertEquals(DashState.CONNECTING, s.state.value)
        s.fail("Auth timed out — the dash answered but never accepted")

        assertEquals(
            listOf("Dash stopped responding — connection lost", "Auth timed out — the dash answered but never accepted"),
            reported.toList(),
        )
        assertEquals(DashState.ERROR, s.state.value)
    }

    private companion object {
        const val JOIN_MS = 10_000L
    }
}
