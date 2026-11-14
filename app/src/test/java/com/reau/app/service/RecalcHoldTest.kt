package com.reau.app.service

import com.reau.app.aa.NavGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A short "inactive" of the route shown as a recalculation, on the switch; with it off every guidance goes straight through. */
class RecalcHoldTest {
    private class Timer {
        val waiting = mutableListOf<Pair<Long, () -> Unit>>()
        val cancelled = mutableListOf<Long>()
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            waiting += delayMs to action
            return { cancelled += delayMs }
        }
        fun fire() = waiting.removeAt(waiting.lastIndex).second()
    }

    private val timer = Timer()
    private val delivered = mutableListOf<NavGuidance>()
    private val said = mutableListOf<String>()
    private var on = true
    private var now = 1_000L
    private val hold = RecalcHold({ on }, { delivered += it }, timer::schedule, clockMs = { now }, log = { said += it })

    private val driving = NavGuidance(active = true, maneuverType = 6, distanceToManeuverMeters = 400, distanceRemainingMeters = 5_000)
    private val byStatus = NavGuidance.INACTIVE_BY_STATUS

    @Test
    fun `with the switch off every guidance goes straight through, an inactive one too, and no timer is ever set`() {
        on = false
        hold.offer(driving)
        hold.offer(byStatus)
        hold.offer(NavGuidance.INACTIVE)
        assertEquals(listOf(driving, byStatus, NavGuidance.INACTIVE), delivered)
        assertTrue(delivered[1] === byStatus)
        assertEquals(emptyList<Long>(), timer.waiting.map { it.first })
    }

    @Test
    fun `with it on a short inactive during a route is a recalculation with the last distances`() {
        hold.offer(driving)
        hold.offer(byStatus)
        assertEquals(2, delivered.size)
        assertEquals(driving.copy(rerouting = true), delivered[1])
        assertTrue(delivered[1].active)
        assertEquals(listOf(RecalcHold.HOLD_MS), timer.waiting.map { it.first })
        assertEquals(8_000L, RecalcHold.HOLD_MS)
    }

    @Test
    fun `the next active guidance replaces the recalculation and stops the timer`() {
        hold.offer(driving)
        hold.offer(byStatus)
        val after = driving.copy(distanceToManeuverMeters = 900)
        hold.offer(after)
        assertEquals(after, delivered.last())
        assertEquals(listOf(RecalcHold.HOLD_MS), timer.cancelled)
    }

    @Test
    fun `an inactive that goes on for the whole hold clears the card at the end of it`() {
        hold.offer(driving)
        hold.offer(byStatus)
        timer.fire()
        assertEquals(NavGuidance.INACTIVE, delivered.last())
        assertEquals(3, delivered.size)
        // and a later inactive has no route to be a recalculation of
        hold.offer(byStatus)
        assertEquals(4, delivered.size)
        assertEquals(NavGuidance.INACTIVE, delivered.last())
    }

    @Test
    fun `a second inactive during the hold is not passed on, and does not restart the timer`() {
        hold.offer(driving)
        hold.offer(byStatus)
        hold.offer(byStatus)
        assertEquals(2, delivered.size)
        assertEquals(1, timer.waiting.size)
    }

    @Test
    fun `the end of a session, a cluster stop, or no route before it, clears at once`() {
        hold.offer(NavGuidance.INACTIVE)
        assertEquals(listOf(NavGuidance.INACTIVE), delivered)
        hold.offer(byStatus)
        assertEquals("no route was running: nothing to hold", 2, delivered.size)
        assertFalse(delivered[1].active)
        hold.offer(driving)
        hold.offer(NavGuidance.INACTIVE)
        assertFalse("not by the status message: the session ended", delivered.last().active)
        assertEquals(emptyList<Long>(), timer.waiting.map { it.first })
    }

    @Test
    fun `an inactive that ends a hold from outside clears at once and the timer is stopped`() {
        hold.offer(driving)
        hold.offer(byStatus)
        hold.offer(NavGuidance.INACTIVE)
        assertEquals(NavGuidance.INACTIVE, delivered.last())
        assertEquals(listOf(RecalcHold.HOLD_MS), timer.cancelled)
    }

    @Test
    fun `a route that has arrived is not held`() {
        for (arrival in 39..42) {
            val before = delivered.size
            hold.offer(driving.copy(maneuverType = arrival))
            hold.offer(byStatus)
            assertFalse("arrival type $arrival", delivered.last().active)
            assertEquals(before + 2, delivered.size)
        }
        hold.offer(driving.copy(distanceRemainingMeters = 100))
        hold.offer(byStatus)
        assertFalse("within 100 m of the destination", delivered.last().active)
        hold.offer(driving.copy(distanceRemainingMeters = 101))
        hold.offer(byStatus)
        assertTrue("101 m: a recalculation", delivered.last().rerouting)
    }

    @Test
    fun `turning the switch off during the hold lets the next guidance through as it is`() {
        hold.offer(driving)
        hold.offer(byStatus)
        on = false
        hold.offer(NavGuidance.INACTIVE)
        assertEquals(NavGuidance.INACTIVE, delivered.last())
        assertEquals(listOf(RecalcHold.HOLD_MS), timer.cancelled)
    }

    @Test
    fun `an old timer does not clear a newer hold`() {
        hold.offer(driving)
        hold.offer(byStatus)
        val first = timer.waiting.last().second
        hold.offer(driving)
        hold.offer(byStatus)
        val deliveredBefore = delivered.size
        first()
        assertEquals("the first hold's timer is stale", deliveredBefore, delivered.size)
        timer.fire()
        assertEquals(NavGuidance.INACTIVE, delivered.last())
    }

    @Test
    fun `the log says when the inactive is held, when the guidance is back and when it never came`() {
        hold.offer(driving)
        assertEquals("nothing is said while the route runs", emptyList<String>(), said)
        hold.offer(byStatus)
        assertEquals(listOf("route: a short inactive shown as a recalculation; holding it up to 8 s (experiment)"), said)
        now += 3_200
        hold.offer(driving)
        assertEquals("route: the guidance is back after 3.2 s of inactive (experiment)", said.last())
        hold.offer(byStatus)
        timer.fire()
        assertEquals("route: the inactive did not end in 8 s; the route card is cleared (experiment)", said.last())
        assertEquals(4, said.size)
    }

    @Test
    fun `with the switch off or with a real end nothing is said`() {
        on = false
        hold.offer(driving)
        hold.offer(byStatus)
        on = true
        hold.offer(NavGuidance.INACTIVE)
        hold.offer(driving.copy(maneuverType = 39))
        hold.offer(byStatus)
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `the service hands the receiver's guidance to the hold, which hands it to the dash`() {
        val live = java.io.File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("on = { tuned?.experimentRecalcInactive == true }, deliver = show,"))
        assertTrue(live.contains("val guidance: (com.reau.app.aa.NavGuidance) -> Unit = recalc::offer"))
        assertTrue(live.contains("clockMs = { android.os.SystemClock.elapsedRealtime() }, log = { RideDiagnostics.log(\"dash\", it) },"))
    }
}
