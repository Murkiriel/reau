package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * A session that connect() has replaced must never touch the one after it.
 *
 * Before the fix, fail() cancelled the receive loop and the heartbeats but not the session coroutine itself (nor the
 * stats line and the cards), and connect() launched a new session coroutine without ending the old one. So a session
 * that failed during authentication kept waiting for the dash's answer, and when the projection had meanwhile asked
 * for the dash again (2 s later), the leftover's own fail(), up to 15 s later, closed the NEW session's socket and
 * put it in ERROR; or its READY went out on a socket already closed.
 *
 * The socket factory here holds each session coroutine at its first step behind a latch of its own (the pattern of
 * DashSessionTest), so the test decides when a leftover session wakes up and what it finds.
 */
class DashSessionReplacedSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    private var session: DashSession? = null
    private val reported = CopyOnWriteArrayList<String>()

    /** Each connect() waits at a gate of its own; released, the factory throws what a taken port throws. */
    private fun heldSession(): DashSession = DashSession(scope, socketFactory = {
        val gate = CountDownLatch(1).also { gates += it }
        gate.await()
        throw java.net.BindException("socket factory held closed by the test")
    }).also { it.onError = { why -> reported += why }; session = it }

    @After
    fun tearDown() {
        session?.disconnect()
        gates.forEach { it.countDown() }
        scope.cancel()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(what, condition())
    }

    @Test
    fun `an old session that fails after it was replaced does not end the new one`() {
        val s = heldSession()
        s.connect("RE_TEST_1234")
        await("the first session reached its socket factory") { gates.size == 1 }
        s.fail("Lost connection to dash — socket error: ENETUNREACH")
        assertEquals(DashState.ERROR, s.state.value)
        assertEquals(listOf("Lost connection to dash — socket error: ENETUNREACH"), reported)

        // The projection's retry, 2 s later: a new session.
        s.connect("RE_TEST_1234")
        assertEquals(DashState.CONNECTING, s.state.value)

        // The old session coroutine wakes up (as it did at its 15 s authentication deadline) and fails.
        gates[0].countDown()
        Thread.sleep(200)

        assertEquals("the new session must still be connecting", DashState.CONNECTING, s.state.value)
        assertEquals("the old session's failure never reaches the rider as the new one's", 1, reported.size)
    }

    @Test
    fun `a failure reported with an earlier session's number is ignored`() {
        val s = heldSession()
        s.connect("RE_TEST_1234")
        assertEquals(DashState.CONNECTING, s.state.value)

        s.fail("Auth timed out — the dash answered but never accepted", generation = 0)

        assertEquals(DashState.CONNECTING, s.state.value)
        assertTrue("nothing reached the rider: $reported", reported.isEmpty())
    }

    @Test
    fun `fail() ends the session coroutine too, and connect() ends what an earlier session left running`() {
        val s = heldSession()
        s.connect("RE_TEST_1234")
        await("the session coroutine is running") { "session" in s.testOnlyActiveJobs() }

        s.fail("Dash stopped responding — connection lost")

        assertFalse("the session coroutine is cancelled by fail(): ${s.testOnlyActiveJobs()}", "session" in s.testOnlyActiveJobs())
        s.connect("RE_TEST_1234")
        await("the new session coroutine is running") { "session" in s.testOnlyActiveJobs() }
        await("the second connect() reached the factory on its own") { gates.size == 2 }
    }

    /**
     * With real sockets on the fixed ports (as DashSocketTest opens them), the session gets as far as AUTHENTICATING
     * and launches its receive loop, its heartbeat and its stats line; a failure then must end every one of them.
     * Before the fix the stats line went on writing a dash-stats line every 5 s after ERROR.
     */
    @Test
    fun `a failure while authenticating ends every coroutine of the session, the stats line included`() {
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)
        val s = DashSession(scope, socketFactory = { DashSocket(it, ctrlDest = loopback, rtpDest = loopback) }, statsEveryMs = 100)
            .also { it.onError = { why -> reported += why }; session = it }
        s.connect("RE_TEST_1234")
        await("the session is authenticating") { s.state.value == DashState.AUTHENTICATING }
        await("the session's coroutines are up: ${s.testOnlyActiveJobs()}") { s.testOnlyActiveJobs().containsAll(setOf("rx", "heartbeat", "stats")) }

        s.fail("Dash stopped responding — connection lost")

        await("every coroutine of the failed session ends: ${s.testOnlyActiveJobs()}") { s.testOnlyActiveJobs().isEmpty() }
        assertEquals(DashState.ERROR, s.state.value)
        // Real sockets on the loopback: the receive loop can see the refused port and fail the session before this
        // test's own fail(), so the one report is either's. What matters is that exactly one reaches the rider.
        // The report is delivered from the failing coroutine, so wait for it (a slow runner can be late) and then
        // give a second report time to show up before counting.
        await("a failure reaches the rider: $reported") { reported.isNotEmpty() }
        Thread.sleep(300)
        assertEquals("one failure reaches the rider: $reported", 1, reported.size)
        assertTrue(
            reported.single(),
            reported.single() == "Dash stopped responding — connection lost" || reported.single().startsWith("Lost connection to dash — socket error"),
        )
    }

    @Test
    fun `cancellation of the session coroutine is not a failure of the next session`() {
        val s = heldSession()
        s.connect("RE_TEST_1234")
        await("the first session reached its socket factory") { gates.size == 1 }
        s.disconnect()
        assertEquals(DashState.IDLE, s.state.value)

        s.connect("RE_TEST_1234")
        await("the second session reached its socket factory") { gates.size == 2 }
        // The first coroutine, cancelled by disconnect(), wakes up in its factory and ends; the second is untouched.
        gates[0].countDown()
        Thread.sleep(200)
        assertEquals(DashState.CONNECTING, s.state.value)
        assertTrue("got $reported", reported.isEmpty())
    }
}
