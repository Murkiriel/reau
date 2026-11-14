package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ride log of 31/10/2026: the session's sockets were refused on network 454 with EPERM, ten times in 4.5 minutes, and the network that
 * refused them was handed back each time. The session now tells whoever holds the network, before it reports the failure.
 */
class DashSessionBindRefusalTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private val events = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
    }

    private fun await(what: String, until: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!until()) {
            assertTrue("timed out waiting for $what: $events", System.nanoTime() < end)
            Thread.sleep(10)
        }
    }

    private fun open(factory: (android.net.Network?) -> DashSocket): DashSession =
        DashSession(scope, socketFactory = factory).also {
            it.onBindResult = { _, refused -> events += "bind refused=$refused" }
            it.onError = { why -> events += "error: $why" }
            session = it
        }

    @Test
    fun `an EPERM on the network is told to the holder of the network before the failure is reported`() {
        val s = open { throw SocketException("Binding socket to network 454 failed: EPERM (Operation not permitted)") }
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals("bind refused=true", events[0])
        assertTrue(events[1], events[1].startsWith("error: SocketException: Binding socket to network 454 failed: EPERM"))
        assertEquals(DashState.ERROR, s.state.value)
    }

    @Test
    fun `another socket error is a failure and no word about the network`() {
        val s = open { throw SocketException("Address already in use") }
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals(1, events.size)
    }

    @Test
    fun `sockets that opened are told too, so a run of refusals starts over`() {
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)
        val s = open { DashSocket(it, ctrlDest = loopback, rtpDest = loopback) }
        s.connect("RE_TEST_1234")
        await("the sockets") { events.contains("bind refused=false") }
        assertEquals("bind refused=false", events[0])
    }
}

/**
 * The experimental retries of a refused bind ([BindRetry]): on the same network, up to 5 times, before the refusal goes on; off, the first
 * refusal goes on as it always did.
 */
class DashSessionBindRetryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private val events = CopyOnWriteArrayList<String>()
    private val attempts = java.util.concurrent.atomic.AtomicInteger()
    private val threads = CopyOnWriteArrayList<String>()
    private val eperm = SocketException("Binding socket to network 454 failed: EPERM (Operation not permitted)")
    private val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
    }

    private fun await(what: String, until: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!until()) {
            assertTrue("timed out waiting for $what: $events", System.nanoTime() < end)
            Thread.sleep(10)
        }
    }

    /** A session whose sockets are refused the first [refusals] times (then open, on the loopback), with the retry switch [on] and a short wait. */
    private fun open(refusals: Int, on: Boolean): DashSession = DashSession(
        scope,
        socketFactory = {
            threads += Thread.currentThread().name
            if (attempts.incrementAndGet() <= refusals) throw eperm else DashSocket(it, ctrlDest = loopback, rtpDest = loopback)
        },
        timing = DashSession.Timing(bindRetryPauseMs = 20),
        bindRetry = { on },
    ).also {
        it.onBindResult = { _, refused -> events += "bind refused=$refused" }
        it.onError = { why -> events += "error: $why" }
        session = it
    }

    @Test
    fun `off, the first refusal goes on at once and there is no second try`() {
        val s = open(refusals = 100, on = false)
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals(1, attempts.get())
        assertEquals("bind refused=true", events[0])
    }

    @Test
    fun `on, a refusal that goes away is retried on the same network and the sockets open without the network ever being let go`() {
        val s = open(refusals = 3, on = true)
        s.connect("RE_TEST_1234")
        await("the sockets") { events.contains("bind refused=false") }
        assertEquals(4, attempts.get())
        assertEquals(listOf("bind refused=false"), events.toList())
    }

    @Test
    fun `on, a refusal that stays is retried 5 times (6 tries in all) and only then goes on as before`() {
        val s = open(refusals = 100, on = true)
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals(6, attempts.get())
        assertEquals("bind refused=true", events[0])
        assertEquals(DashState.ERROR, s.state.value)
    }

    @Test
    fun `on, five refusals and the sixth try opens the sockets`() {
        val s = open(refusals = 5, on = true)
        s.connect("RE_TEST_1234")
        await("the sockets") { events.contains("bind refused=false") }
        assertEquals(6, attempts.get())
    }

    @Test
    fun `on, another socket error is not retried`() {
        val s = DashSession(
            scope,
            socketFactory = { attempts.incrementAndGet(); throw SocketException("Address already in use") },
            timing = DashSession.Timing(bindRetryPauseMs = 20), bindRetry = { true },
        ).also { session = it; it.onError = { why -> events += "error: $why" } }
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `the tries are made off the main thread, on the IO threads`() {
        val s = open(refusals = 2, on = true)
        s.connect("RE_TEST_1234")
        await("the sockets") { events.contains("bind refused=false") }
        assertTrue(threads.toString(), threads.all { it != "main" && it.contains("DefaultDispatcher") })
    }

    @Test
    fun `the policy is 5 retries, 400 ms apart, only for a refusal and only with the switch on`() {
        assertEquals(5, BindRetry.MAX_RETRIES)
        assertEquals(400L, BindRetry.PAUSE_MS)
        assertTrue(BindRetry.again(enabled = true, refusal = true, retriesDone = 0))
        assertTrue(BindRetry.again(enabled = true, refusal = true, retriesDone = 4))
        assertEquals(false, BindRetry.again(enabled = true, refusal = true, retriesDone = 5))
        assertEquals(false, BindRetry.again(enabled = false, refusal = true, retriesDone = 0))
        assertEquals(false, BindRetry.again(enabled = true, refusal = false, retriesDone = 0))
    }
}
