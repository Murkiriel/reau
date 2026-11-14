package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A failure to make the info ring's picture took the ring off the video with not a word: the first one of a projection is told. */
class InfoRingFailureTest {
    @Test
    fun `the first failure is told with its kind, the others are not`() {
        val line = InfoRingFeed.failureLine(IllegalArgumentException("Cannot round NaN value."), failuresBefore = 0)
        assertEquals("the info ring could not be made (IllegalArgumentException): it is off the video until it can; told once", line)
        assertNull(InfoRingFeed.failureLine(IllegalStateException(), failuresBefore = 1))
        assertNull(InfoRingFeed.failureLine(IllegalStateException(), failuresBefore = 500))
    }

    @Test
    fun `the loop is handed the warning of the ride log under the ring's tag`() {
        // What the loop does with a failure (counts it, tells the first) runs in InfoRingLoopTest; here only what it is handed, which needs the service.
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("warn = { RideDiagnostics.warn(\"ring\", it) })"))
    }
}
