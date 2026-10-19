package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.data.RideSummary
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ride summary counts of the dash side. A summary once said `drops dash=16` for a ride in
 * which the dash dropped twice: every try to get it back that failed went back to DROPPED and was counted as a drop.
 */
class DashDropsTest {
    @Test
    fun `only a dash that was connected and is gone is a drop`() {
        assertEquals(DashDrops.Kind.DROP, DashDrops.of(DashLink.READY, DashLink.DROPPED))
    }

    @Test
    fun `a try that ends without the dash is a failed try, not a drop`() {
        assertEquals(DashDrops.Kind.FAILED_TRY, DashDrops.of(DashLink.CONNECTING, DashLink.DROPPED))
    }

    @Test
    fun `everything else counts for nothing`() {
        for (before in DashLink.entries) {
            for (after in DashLink.entries) {
                val kind = DashDrops.of(before, after)
                val counted = after == DashLink.DROPPED && (before == DashLink.READY || before == DashLink.CONNECTING)
                assertEquals("$before to $after", !counted, kind == DashDrops.Kind.NONE)
            }
        }
        assertEquals("dropped again is not a new drop", DashDrops.Kind.NONE, DashDrops.of(DashLink.DROPPED, DashLink.DROPPED))
    }

    /** The service counts at each change of state the machine makes; here the real machine makes them. */
    @Test
    fun `two drops and fourteen failed tries are two drops and fourteen retries in the summary`() {
        var state = Projection()
        val summary = RideSummary()
        summary.start(nowMs = 0, batteryPct = null)
        fun on(event: ProjectionEvent, nowMs: Long = 0L) {
            val step = ProjectionMachine.step(state, event, nowMs)
            if (step.state != state) {
                when (DashDrops.of(state.dash, step.state.dash)) {
                    DashDrops.Kind.DROP -> summary.dashDropped()
                    DashDrops.Kind.FAILED_TRY -> summary.dashTryFailed()
                    DashDrops.Kind.NONE -> Unit
                }
            }
            state = step.state
        }

        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
        var now = 0L
        // The dash goes (drop 1); seven tries fail; the eighth gets it back.
        repeat(2) {
            on(DashChanged(DashLink.DROPPED), now)
            repeat(7) {
                now += 5_000
                on(DashRetryDue, now) // the machine starts the dash: CONNECTING
                on(DashChanged(DashLink.DROPPED), now) // the try fails
            }
            now += 5_000
            on(DashRetryDue, now)
            // The same report twice, as the Wi-Fi and the session both say it: nothing more to count.
            on(DashChanged(DashLink.READY), now)
            on(DashChanged(DashLink.READY), now)
        }
        assertEquals(DashLink.READY, state.dash)
        val line = summary.line(nowMs = now, marks = 0)
        assertTrue("got $line", " drops dash=2 aa=0 retries dash=14 " in line)
    }
}
