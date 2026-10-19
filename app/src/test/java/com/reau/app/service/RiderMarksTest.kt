package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/**
 * The rider's mark, a button on the projection's notification that writes a numbered line to the log, and says on the
 * notification that it did.
 */
class RiderMarksTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `each mark is numbered, from one, and its line says so`() {
        val marks = RiderMarks(utc)
        assertEquals("MARCA DO PILOTO 1", marks.mark(nowMs = 0))
        assertEquals("MARCA DO PILOTO 2", marks.mark(nowMs = 1_000))
        assertEquals(2, marks.count)
    }

    @Test
    fun `the notification gets the time of the mark that was taken`() {
        val marks = RiderMarks(utc)
        assertNull("nothing before the first mark", marks.markedAt)
        marks.mark(nowMs = 10 * 3_600_000L + 41 * 60_000L + 7_000L)
        assertEquals("10:41:07", marks.markedAt)
        assertEquals(1, marks.count)
    }

    @Test
    fun `a new projection starts counting again`() {
        val marks = RiderMarks(utc)
        marks.mark(nowMs = 0)
        marks.mark(nowMs = 0)
        marks.reset()
        assertEquals(0, marks.count)
        assertNull(marks.markedAt)
        assertEquals("MARCA DO PILOTO 1", marks.mark(nowMs = 0))
    }
}
