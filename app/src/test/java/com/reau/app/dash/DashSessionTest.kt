package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * DashSession only depends on a CoroutineScope and on the factory that opens its DashSocket, so
 * connect()'s re-entrancy guard and fail()'s rules can be exercised in a plain unit test.
 *
 * The factory given here never opens a socket: it holds the session coroutine at its very first
 * step until tearDown. Without that, these tests raced the coroutine they had just launched. With
 * the real DashSocket it only takes the test thread losing the CPU for a moment after connect()
 * for the session to bind its sockets and move on to AUTHENTICATING, and the "still CONNECTING"
 * assertions then fail. That is what the CI machine showed (Linux, few cores: "expected
 * CONNECTING but was AUTHENTICATING"), never the development PC. Held at the factory, the state
 * right after connect() is CONNECTING by construction, which is exactly the window the guard
 * exists for.
 */
class DashSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private val gate = CountDownLatch(1)

    private fun newSession(): DashSession = DashSession(scope, socketFactory = {
        gate.await()
        // Same exception a taken port gives: the session turns it into fail(), which tearDown's
        // disconnect() has already told to stand down.
        throw java.net.BindException("socket factory held closed by the test")
    }).also { session = it }

    @After
    fun tearDown() {
        // disconnect() first, so the fail() of the coroutines released below is ignored.
        session?.disconnect()
        gate.countDown()
        scope.cancel()
    }

    @Test
    fun `connect() flips to CONNECTING synchronously, before the session coroutine even runs`() {
        val s = newSession()
        assertEquals(DashState.IDLE, s.state.value)

        s.connect("TestSSID")

        // No delay/yield here -- runSession() is a suspend fun dispatched onto Dispatchers.IO and
        // hasn't necessarily even started yet. If CONNECTING were still set from inside it (the
        // pre-fix behavior), this assertion could see IDLE and pass right when it shouldn't.
        assertEquals(DashState.CONNECTING, s.state.value)
    }

    @Test
    fun `a second concurrent connect() call is a no-op while already CONNECTING`() {
        val s = newSession()
        s.connect("TestSSID")
        assertEquals(DashState.CONNECTING, s.state.value)

        // Regression test for the race this fixes: the caller has two independent retry paths
        // (WiFi-reconnect and session-retry) that can both call connect() after an error. Before
        // the fix, _state only flipped to CONNECTING inside the async runSession() coroutine, so
        // both callers could pass this guard, each binding its own DashSocket on the same fixed
        // UDP ports -- the loser's fail() then closed the winner's socket out from under it. Now
        // the flip is synchronous in connect() itself, so this second call must bounce off it
        // without disturbing the first session's state.
        s.connect("AnotherSSID")
        assertEquals(DashState.CONNECTING, s.state.value)
    }

    @Test
    fun `disconnect() resets to IDLE, and IDLE re-opens the guard for a fresh connect()`() {
        val s = newSession()
        s.connect("TestSSID")
        assertEquals(DashState.CONNECTING, s.state.value)

        s.disconnect()
        assertEquals(DashState.IDLE, s.state.value)

        s.connect("TestSSID")
        assertEquals(DashState.CONNECTING, s.state.value)
    }

    @Test
    fun `a second fail() call for the same episode does not overwrite the first diagnosis or re-fire onError`() {
        // Regression test for a real sequence found in a ride log: the
        // RX watchdog's fail() cancels rxJob then closes the socket -- but rxJob was blocked
        // inside a synchronous receive() at that exact moment, so cancel() alone doesn't stop it;
        // the socket close is what unblocks it, with a confusing OS-level error (EBADF) instead of
        // a clean CancellationException. launchReceiveLoop's catch then called fail() AGAIN with
        // that error, silently overwriting the real "Dash stopped responding" diagnosis the rider
        // had already been shown. fail() is internal specifically so this can be driven directly,
        // without needing to actually race a live socket read to reproduce it.
        val s = newSession()
        val reported = mutableListOf<String>()
        s.onError = { reported += it }
        s.connect("TestSSID")
        assertEquals(DashState.CONNECTING, s.state.value)

        s.fail("Dash stopped responding — connection lost")
        s.fail("Lost connection to dash — socket error: recvfrom failed: EBADF (Bad file descriptor)")

        assertEquals(DashState.ERROR, s.state.value)
        assertEquals("only the first, real diagnosis should ever reach the rider", listOf("Dash stopped responding — connection lost"), reported)
    }

    @Test
    fun `fail() after disconnect() is ignored -- IDLE from the clean teardown always wins`() {
        // Regression test for a real race found live (DashSessionFakeDashTest's teardown test):
        //disconnect()'s own sock.close() unblocks rxJob's blocking receive() with an
        // OS-level exception, whose catch block calls fail() -- and that could land AFTER disconnect()'s
        // unconditional `_state = IDLE` write, since the two run on different coroutines. fail() itself
        // is driven directly here (same "internal, tests only" reasoning as the double-fail test above)
        // to prove the fix without needing to actually race a live socket read.
        val s = newSession()
        val reported = mutableListOf<String>()
        s.onError = { reported += it }
        s.connect("TestSSID")

        s.disconnect()
        assertEquals(DashState.IDLE, s.state.value)

        s.fail("Lost connection to dash — socket error: recvfrom failed: EBADF (Bad file descriptor)")

        assertEquals("a stale exception from our own teardown must never resurrect ERROR", DashState.IDLE, s.state.value)
        assertEquals("and must never reach the rider as a real error", emptyList<String>(), reported)
    }
}
