package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry pace of a dash gone: with the bike switched off and the projection on, REau once asked for the dash's
 * network about once a minute for 40 minutes, with no end. Within the first five minutes no wait passes half a minute
 * (the short ones of a quick drop stay, so a dash that comes back at once is back at once); from five minutes on, one
 * try a minute, for as long as the projection is on.
 */
class ProjectionMachinePaceTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent, nowMs: Long = 0L): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event, nowMs)
        state = step.state
        return step.effects
    }

    private fun running() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
    }

    private fun waitAfterDrop(nowMs: Long, link: DashLink = DashLink.DROPPED): Long {
        val wait = on(DashChanged(link), nowMs).filterIsInstance<RetryDashIn>().single().delayMs
        on(DashRetryDue, nowMs + wait)
        return wait
    }

    /**
     * The bike switched off with the projection on: each try asks for the network and fails after the request's 30 s
     * ([TRY_MS]), then waits. The waits from the drop at [fromMs] until [untilMs], with when each came.
     */
    private fun waitsWhileGone(fromMs: Long, untilMs: Long): List<Pair<Long, Long>> {
        val waits = mutableListOf<Pair<Long, Long>>()
        var now = fromMs
        while (now < untilMs) {
            val wait = waitAfterDrop(now)
            waits += now to wait
            now += wait + TRY_MS
        }
        return waits
    }

    @Test
    fun `in the first five minutes no wait passes half a minute, and a quick drop is tried again at once`() {
        running()
        val first = waitsWhileGone(0, 5 * MINUTE).filter { (at, _) -> at < 5 * MINUTE }.map { it.second }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), first.take(5))
        assertTrue("none over half a minute: $first", first.all { it <= 30_000L })
    }

    @Test
    fun `from five minutes gone the dash is tried once a minute, with no end`() {
        running()
        val waits = waitsWhileGone(0, 3 * 60 * MINUTE)
        val late = waits.filter { (at, _) -> at >= 5 * MINUTE }.map { it.second }
        assertTrue("there are tries after five minutes", late.size > 100)
        assertTrue("all once a minute: ${late.distinct()}", late.all { it == 60_000L })
    }

    @Test
    fun `the five minutes start over when the dash comes back`() {
        running()
        waitsWhileGone(0, 10 * MINUTE)
        on(DashChanged(DashLink.READY), 10 * MINUTE)
        val again = waitsWhileGone(10 * MINUTE, 20 * MINUTE)
        assertEquals("a new drop starts quick again", 2_000L, again.first().second)
        assertTrue("half a minute at most in its first five minutes",
            again.filter { (at, _) -> at < 15 * MINUTE }.all { it.second <= 30_000L })
        assertTrue("once a minute after them", again.filter { (at, _) -> at >= 15 * MINUTE }.all { it.second == 60_000L })
    }

    @Test
    fun `a dash taken by another app follows the same pace`() {
        running()
        assertEquals(2_000L, waitAfterDrop(0, DashLink.TAKEN))
        assertEquals(60_000L, waitAfterDrop(6 * MINUTE, DashLink.TAKEN))
    }

    @Test
    fun `switching off forgets when the dash went`() {
        running()
        waitAfterDrop(0)
        on(Stop, MINUTE)
        on(ProjectionEvent.StopDone, MINUTE)
        running()
        assertEquals(2_000L, waitAfterDrop(20 * MINUTE))
    }

    @Test
    fun `the wait by attempt and by time gone`() {
        assertEquals(30_000L, ProjectionMachine.dashRetryDelayMs(10, goneMs = 5 * MINUTE - 1))
        assertEquals(60_000L, ProjectionMachine.dashRetryDelayMs(10, goneMs = 5 * MINUTE))
        assertEquals(2_000L, ProjectionMachine.dashRetryDelayMs(1, goneMs = 0))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val TRY_MS = 30_000L
    }
}
