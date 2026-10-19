package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The log line of a receiver that connected after dials that found nothing. */
class RefusedDialsTest {
    @Test
    fun `a connection with no refusal before says nothing`() {
        assertNull(RefusedDials(callIn = false).connected(10_000L))
    }

    @Test
    fun `the refusals and the time from the first one are told once the connection comes`() {
        val dials = RefusedDials(callIn = false)
        dials.refused(1_000L)
        dials.refused(3_000L)
        dials.refused(5_000L)
        assertEquals(3, dials.count)
        assertEquals("connected at last after 3 dial(s) refused in 7.5 s", dials.connected(8_500L))
        assertEquals("starts again", 0, dials.count)
        assertNull("said once", dials.connected(9_000L))
    }

    @Test
    fun `a later streak counts from its own first refusal`() {
        val dials = RefusedDials(callIn = false)
        dials.refused(1_000L)
        dials.connected(2_000L)
        dials.refused(50_000L)
        assertEquals("connected at last after 1 dial(s) refused in 0.2 s", dials.connected(50_200L))
    }

    @Test
    fun `the old Android Auto that is asked to call says so`() {
        val dials = RefusedDials(callIn = true)
        dials.refused(0L)
        dials.refused(4_000L)
        assertEquals("connected at last after 2 ask(s) for a call unanswered in 9.0 s", dials.connected(9_000L))
    }
}
