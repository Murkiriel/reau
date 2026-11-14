package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls that turn the GPS off live in [LiveSides], which needs a device to run; this keeps them in place (26/10/2026 review, A-02: the
 * GPS stayed on after "Desligar" until the process died; the first step of the fix is [GpsWatcherTest]'s, these are the callers).
 */
class GpsStopsWithTheProjectionTest {
    private val source = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()

    @Test
    fun `the release of the projection gives the GPS back`() {
        val release = source.substringAfter("override fun release() {").substringBefore("override fun ", missingDelimiterValue = "")
        assertTrue("release() is not where it was", release.contains("ReleaseSteps.runAll"))
        assertEquals("one step stops the GPS, named", 1, Regex("""\"gps\" to \{ GpsWatch\.stop\(\) \}""").findAll(release).count())
    }

    @Test
    fun `the ring's loop is handed the GPS's stop`() {
        // That the loop calls it when the ring is off the video runs in InfoRingLoopTest; here only what it is handed, which needs the service.
        assertTrue(source.contains("InfoRingFeed.Loop(RING_TICKS_PER_LOOK, stopGps = { GpsWatch.stop() },"))
    }
}
