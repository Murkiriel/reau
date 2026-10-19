package com.reau.app.aa

import com.reau.app.aa.ServerPolicy.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the server is started, restarted and switched off, and the notification's "Stop". */
class ServerPolicyTest {
    private val policy = ServerPolicy().apply { started() }

    @Test
    fun `the first closed port starts the server, once per switching on`() {
        assertEquals(Act.NOTHING, policy.state(AaLinkState.CONNECTING))
        assertEquals(Act.START, policy.state(AaLinkState.SERVER_OFF))
        assertEquals(Act.ANSWERED, policy.state(AaLinkState.CONNECTING))
        assertEquals(Act.NOTHING, policy.state(AaLinkState.DROPPED))
        assertEquals(Act.NOTHING, policy.state(AaLinkState.SERVER_OFF))
        policy.started()
        assertEquals(Act.START, policy.state(AaLinkState.SERVER_OFF))
    }

    @Test
    fun `the service that connects retries by itself only under the server card of a live projection`() {
        val refused = ServerResult.SERVICE_OFF
        assertFalse("the port was never seen closed", policy.retryOnServiceConnected(refused))
        assertEquals(Act.START, policy.state(AaLinkState.SERVER_OFF))
        assertTrue(policy.retryOnServiceConnected(refused))
        assertFalse("another result is not 'the service was off'", policy.retryOnServiceConnected(ServerResult.LOCKED))
        assertFalse("nor a walk already going on", policy.retryOnServiceConnected(ServerResult.WORKING))
        // The port answered: the card is gone.
        assertEquals(Act.ANSWERED, policy.state(AaLinkState.CONNECTING))
        assertFalse(policy.retryOnServiceConnected(refused))
    }

    @Test
    fun `the service that connects after the projection ended, or under a newer one, does not retry`() {
        val first = policy.started()
        policy.state(AaLinkState.SERVER_OFF, first)
        assertTrue(policy.retryOnServiceConnected(ServerResult.SERVICE_OFF))
        policy.stopped(first)
        assertFalse("projection over", policy.retryOnServiceConnected(ServerResult.SERVICE_OFF))
        val second = policy.started()
        assertFalse("a new projection has not seen the closed port yet", policy.retryOnServiceConnected(ServerResult.SERVICE_OFF))
        policy.stopped(first)
        policy.state(AaLinkState.SERVER_OFF, second)
        assertTrue("an older end changes nothing of the newer one", policy.retryOnServiceConnected(ServerResult.SERVICE_OFF))
    }

    @Test
    fun `a dial that goes through without a closed port before is not an answer`() {
        assertEquals(Act.NOTHING, policy.state(AaLinkState.CONNECTING))
        assertEquals(Act.NOTHING, policy.state(AaLinkState.ACTIVE))
        assertEquals(Act.NOTHING, policy.state(AaLinkState.CONNECTING))
    }

    @Test
    fun `Android Auto that does not answer is restarted once`() {
        assertEquals(Act.RESTART, policy.state(AaLinkState.UNRESPONSIVE))
        assertEquals(Act.NOTHING, policy.state(AaLinkState.UNRESPONSIVE))
        policy.started()
        assertEquals(Act.RESTART, policy.state(AaLinkState.UNRESPONSIVE))
    }

    @Test
    fun `an Android Auto still closing the previous session is waited for, not restarted`() {
        // A restart inside the window (the server stopped and started again) is what brought Android Auto down with
        // "Already connected": the screen says it clears by itself, and the policy does nothing meanwhile.
        repeat(3) { assertEquals(Act.NOTHING, policy.state(AaLinkState.CLOSING_PREVIOUS)) }
        assertTrue("it was seen up: the end of the projection switches it off", policy.serverSeenUp())
    }

    @Test
    fun `when the window ends and Android Auto is still mute, the old behaviour comes back once`() {
        assertEquals(Act.NOTHING, policy.state(AaLinkState.CLOSING_PREVIOUS))
        assertEquals("stuck for real: the one restart was not spent by the wait", Act.RESTART, policy.state(AaLinkState.UNRESPONSIVE))
        assertEquals(Act.NOTHING, policy.state(AaLinkState.UNRESPONSIVE))
        assertEquals("back in the window (a new streak): still nothing", Act.NOTHING, policy.state(AaLinkState.CLOSING_PREVIOUS))
        assertTrue("it was seen up", policy.stopped())
    }

    @Test
    fun `a port that closes while the previous session is closing is started once`() {
        assertEquals(Act.NOTHING, policy.state(AaLinkState.CLOSING_PREVIOUS))
        assertEquals(Act.START, policy.state(AaLinkState.SERVER_OFF))
        assertEquals(Act.ANSWERED, policy.state(AaLinkState.CONNECTING))
    }

    @Test
    fun `the closing wait of an older projection changes nothing of the newer one`() {
        val old = policy.started()
        val fresh = policy.started()
        assertEquals(Act.NOTHING, policy.state(AaLinkState.CLOSING_PREVIOUS, old))
        assertFalse("not marked up by the older projection", policy.stopped(fresh))
    }

    @Test
    fun `the server is switched off only when it was seen up`() {
        policy.state(AaLinkState.SERVER_OFF)
        assertFalse(policy.stopped())
        policy.started()
        policy.state(AaLinkState.CONNECTING)
        policy.state(AaLinkState.ACTIVE)
        assertTrue(policy.stopped())
        assertFalse(policy.stopped())
    }

    @Test
    fun `the stop of a projection that ended after a newer one began leaves the server on`() {
        // Off and on again in under 2 s, the old projection's stop came after
        // the new one's start and switched the server off under it; it only came back some 5 min later.
        val old = ServerPolicy()
        val first = old.started()
        old.state(AaLinkState.ACTIVE, first)
        val second = old.started()
        old.state(AaLinkState.ACTIVE, second)
        assertFalse("the old projection's stop", old.stopped(first))
        assertTrue("the new projection's own stop", old.stopped(second))
    }

    @Test
    fun `the link states of an older projection do not touch the newer one`() {
        val p = ServerPolicy()
        val first = p.started()
        val second = p.started()
        assertEquals(Act.NOTHING, p.state(AaLinkState.SERVER_OFF, first))
        assertEquals(Act.NOTHING, p.state(AaLinkState.UNRESPONSIVE, first))
        assertFalse("an old ACTIVE does not mark the new one's server as seen up", p.stopped(second))
        assertEquals(Act.START, p.state(AaLinkState.SERVER_OFF, second))
    }

    @Test
    fun `each projection gets its own number`() {
        val p = ServerPolicy()
        val first = p.started()
        assertTrue(p.started() != first)
    }

    @Test
    fun `a server that came up after the start is switched off at the end`() {
        policy.state(AaLinkState.SERVER_OFF)
        policy.state(AaLinkState.CONNECTING)
        assertTrue(policy.stopped())
    }

    @Test
    fun `a restart refused for want of the service is made again when the service connects, while the link is still mute`() {
        val refused = ServerResult.SERVICE_OFF
        assertFalse("nothing mute yet", policy.retryOnServiceConnected(refused))
        assertEquals(Act.RESTART, policy.state(AaLinkState.UNRESPONSIVE))
        assertTrue("the one restart was spent on a refusal: it comes back with the service", policy.retryOnServiceConnected(refused))
        assertEquals("the retry is no second restart of the policy", Act.NOTHING, policy.state(AaLinkState.UNRESPONSIVE))
        assertTrue("still mute", policy.retryOnServiceConnected(refused))
        assertFalse("another refusal is not 'the service was off'", policy.retryOnServiceConnected(ServerResult.LOCKED))
        assertFalse("a walk already going on", policy.retryOnServiceConnected(ServerResult.WORKING))
        assertFalse("a walk that ended", policy.retryOnServiceConnected(ServerResult.STARTED))
    }

    @Test
    fun `the mute retry never touches a link that answered, closed its port or began again`() {
        val refused = ServerResult.SERVICE_OFF
        // Android Auto answers (a session that works must not be dropped by a restart).
        policy.state(AaLinkState.UNRESPONSIVE)
        policy.state(AaLinkState.ACTIVE)
        assertFalse("answered", policy.retryOnServiceConnected(refused))
        // A session that ended after an answer.
        policy.state(AaLinkState.UNRESPONSIVE)
        policy.state(AaLinkState.DROPPED)
        assertFalse("dropped", policy.retryOnServiceConnected(refused))
        // Back in the wait for the previous session to close: the restart is not due (the window is passive).
        policy.state(AaLinkState.UNRESPONSIVE)
        policy.state(AaLinkState.CLOSING_PREVIOUS)
        assertFalse("closing", policy.retryOnServiceConnected(refused))
        // The projection ended, or a new one began: nothing mute is carried over.
        policy.state(AaLinkState.UNRESPONSIVE)
        assertTrue(policy.retryOnServiceConnected(refused))
        policy.stopped()
        assertFalse("projection over", policy.retryOnServiceConnected(refused))
        policy.started()
        assertFalse("a new projection has not been mute yet", policy.retryOnServiceConnected(refused))
    }

    @Test
    fun `the mute of an older projection does not make the newer one retry`() {
        val old = policy.started()
        val fresh = policy.started()
        assertEquals(Act.NOTHING, policy.state(AaLinkState.UNRESPONSIVE, old))
        assertFalse(policy.retryOnServiceConnected(ServerResult.SERVICE_OFF))
        assertEquals(Act.RESTART, policy.state(AaLinkState.UNRESPONSIVE, fresh))
        assertTrue(policy.retryOnServiceConnected(ServerResult.SERVICE_OFF))
    }

    @Test
    fun `the mute server is restarted by the rider unless the full edition has the switch on and the service connected`() {
        assertFalse("full edition, switch on, service connected: REau does it", MuteServerFix.byRider(hasAccessibility = true, auto = true, serviceConnected = true))
        assertTrue("full edition, service not connected", MuteServerFix.byRider(hasAccessibility = true, auto = true, serviceConnected = false))
        assertTrue("full edition, switch off", MuteServerFix.byRider(hasAccessibility = true, auto = false, serviceConnected = true))
        assertTrue("full edition, both off", MuteServerFix.byRider(hasAccessibility = true, auto = false, serviceConnected = false))
        for (auto in listOf(false, true)) for (connected in listOf(false, true)) {
            assertTrue("Lite edition: $auto $connected", MuteServerFix.byRider(hasAccessibility = false, auto = auto, serviceConnected = connected))
        }
    }

    @Test
    fun `the gate asks for the service first, then the phone unlocked, then Android Auto`() {
        assertEquals(ServerResult.SERVICE_OFF, ServerGate.refusal(ServerGate.Phone(serviceConnected = false, locked = true, androidAutoSettings = false)))
        assertEquals(ServerResult.LOCKED, ServerGate.refusal(ServerGate.Phone(serviceConnected = true, locked = true, androidAutoSettings = false)))
        assertEquals(ServerResult.NOT_INSTALLED, ServerGate.refusal(ServerGate.Phone(serviceConnected = true, locked = false, androidAutoSettings = false)))
        assertNull(ServerGate.refusal(ServerGate.Phone(serviceConnected = true, locked = false, androidAutoSettings = true)))
    }

    @Test
    fun `each outcome says something to the rider, and a touched start waits for the port`() {
        assertEquals(ServerResult.WORKING, ServerResult.of(ServerMenuRun.Outcome.TAPPED_START))
        assertEquals(ServerResult.STOPPED, ServerResult.of(ServerMenuRun.Outcome.TAPPED_STOP))
        assertEquals(ServerResult.DEVELOPER_MODE_OFF, ServerResult.of(ServerMenuRun.Outcome.DEVELOPER_MODE_OFF))
        assertEquals(ServerResult.INTERRUPTED, ServerResult.of(ServerMenuRun.Outcome.LOCKED))
        ServerMenuRun.Outcome.entries.forEach { assertTrue(it.name, ServerResult.of(it) != ServerResult.NONE) }
    }

    @Test
    fun `the port answering after the touched start ends the wait`() {
        val wait = PortWait()
        wait.walkBegun()
        assertFalse(wait.tapped(1_000))
        assertEquals(30L, wait.answered(1_030))
        assertFalse(wait.expired(1_000))
    }

    @Test
    fun `the port answering during the walk counts when the walk ends`() {
        val wait = PortWait()
        wait.walkBegun()
        assertNull(wait.answered(900))
        assertTrue(wait.tapped(1_000))
        assertFalse(wait.expired(1_000))
    }

    @Test
    fun `a port that never answers runs the wait out, once`() {
        val wait = PortWait()
        wait.walkBegun()
        assertFalse(wait.tapped(1_000))
        assertTrue(wait.expired(1_000))
        assertFalse(wait.expired(1_000))
        assertNull(wait.answered(7_000))
    }

    @Test
    fun `an answer outside any walk is not kept for the next one`() {
        val wait = PortWait()
        assertNull(wait.answered(500))
        // A walk that touched nothing: an answer during it does not count for the next walk.
        wait.walkBegun()
        assertNull(wait.answered(600))
        wait.walkEnded()
        wait.walkBegun()
        assertFalse(wait.tapped(1_000))
    }

    @Test
    fun `an older wait running out does not end a newer one`() {
        val wait = PortWait()
        wait.walkBegun()
        wait.tapped(1_000)
        wait.walkBegun()
        assertFalse(wait.tapped(9_000))
        assertFalse(wait.expired(1_000))
        assertTrue(wait.expired(9_000))
    }

    private val texts = ServerTexts.table()
    private val server = ServerNotification.Seen(
        channel = ServerNotification.CHANNEL, actions = 1, creator = AndroidAuto.PACKAGE, isService = true,
        actionTitle = "Parar", text = "Servidor da unidade principal em execução",
    )

    @Test
    fun `the server's notification is known by its channel, one action from Android Auto, and its words`() {
        assertTrue(ServerNotification.isServerStop(server, texts))
        assertTrue(ServerNotification.isServerStop(server.copy(isService = null), texts))
        assertTrue(ServerNotification.isServerStop(server.copy(actionTitle = "Abrir"), texts))
        assertTrue(ServerNotification.isServerStop(server.copy(text = null), texts))
    }

    @Test
    fun `other notifications of Android Auto are not taken`() {
        assertFalse(ServerNotification.isServerStop(server.copy(channel = "other"), texts))
        assertFalse(ServerNotification.isServerStop(server.copy(actions = 2), texts))
        assertFalse(ServerNotification.isServerStop(server.copy(creator = "com.other"), texts))
        assertFalse(ServerNotification.isServerStop(server.copy(isService = false), texts))
        assertFalse(ServerNotification.isServerStop(server.copy(actionTitle = "Conectar", text = "Conectado ao carro"), texts))
    }
}
