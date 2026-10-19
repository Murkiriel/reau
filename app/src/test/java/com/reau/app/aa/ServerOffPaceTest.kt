package com.reau.app.aa

import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ProjectionEvent
import com.reau.app.service.ProjectionMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long the rider waits before the screen says the head unit server is off. A closed port refuses at once, so the
 * receiver says [AaLinkState.SERVER_OFF] after the first dial that fails (never more than its dial timeout), and the
 * machine turns it into the warning in the same step, with nothing to wait for. The long waits of the receiver are
 * other cases, not this one: Android Auto accepting the connection and not answering (three handshakes of 10 s) and the
 * Lite's call-in for Android Auto 17.3 and older (five asks of 8 s).
 */
class ServerOffPaceTest {
    @Test
    fun `the first dial that fails says the server is off, within the dial timeout`() {
        val timing = AaReceiver.Timing()
        assertTrue("dial timeout ${timing.dialTimeoutMs} ms", timing.dialTimeoutMs <= 1_000)
        // Only the pause between dials comes after the report, not before it.
        assertTrue("redial ${timing.redialMs} ms", timing.redialMs <= 2_000)
    }

    @Test
    fun `the machine shows the warning in the very step that hears the report`() {
        val running = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.CONNECTING)
        val step = ProjectionMachine.step(running, ProjectionEvent.AndroidAutoChanged(AaLinkState.SERVER_OFF))
        assertEquals(Headline.ANDROID_AUTO_SERVER_OFF, step.state.headline)
        assertTrue("nothing is scheduled: ${step.effects}", step.effects.isEmpty())
    }

    @Test
    fun `the slow waits belong to the other cases, and are told apart from a closed port`() {
        // 17.3 and older, asked to dial in: five asks of 8 s say NOT_CALLING (about 40 s), never SERVER_OFF.
        assertEquals(40_000, AaReceiver.CALL_IN_TRIES_BEFORE_SAYING * AaReceiver.CALL_IN_WAIT_MS)
        // Accepting and not answering: three unanswered handshakes in a row say UNRESPONSIVE, never SERVER_OFF.
        val streak = HandshakeStreak()
        assertEquals(false, streak.after(handshook = false))
        assertEquals(false, streak.after(handshook = false))
        assertEquals(true, streak.after(handshook = false))
    }
}
