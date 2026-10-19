package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SolarClockTest {

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }

    // ── elevationDeg() — verifiable properties, no external reference table needed ──────────

    @Test
    fun `sun is high overhead near the equator at local solar noon on the equinox`() {
        // lng=0 -> local solar noon is ~12:00 UTC; equinox -> declination near 0, so at the
        // equator elevation should be close to 90 degrees.
        val elevation = SolarClock.elevationDeg(lat = 0.0, lng = 0.0, epochMs = utcMs(2026, 3, 20, 12))
        assertTrue("expected elevation near 90 at equatorial solar noon on equinox, got $elevation", elevation > 80.0)
    }

    @Test
    fun `sun is well below the horizon at local solar midnight anywhere`() {
        // lng=0 -> local solar midnight is ~00:00 UTC, any latitude.
        val elevation = SolarClock.elevationDeg(lat = -23.5, lng = 0.0, epochMs = utcMs(2026, 3, 20, 0))
        assertTrue("expected elevation well below horizon at solar midnight, got $elevation", elevation < -60.0)
    }

    @Test
    fun `longitude shifts local solar noon by 4 minutes per degree, roughly`() {
        // 90 degrees east means local solar noon happens 6 hours earlier in UTC (90 / 15 = 6h).
        val atNoonUtc = SolarClock.elevationDeg(lat = 0.0, lng = 90.0, epochMs = utcMs(2026, 3, 20, 6))
        assertTrue("expected elevation near 90 at that longitude's own solar noon, got $atNoonUtc", atNoonUtc > 80.0)
    }

    @Test
    fun `elevation stays within the physically valid -90 to 90 range`() {
        val samples = listOf(
            SolarClock.elevationDeg(89.9, 0.0, utcMs(2026, 6, 21, 0)),
            SolarClock.elevationDeg(-89.9, 0.0, utcMs(2026, 12, 21, 12)),
            SolarClock.elevationDeg(0.0, 180.0, utcMs(2026, 1, 1, 0)),
        )
        for (e in samples) assertTrue("elevation $e out of range", e in -90.0..90.0)
    }

    // ── anchors from plain astronomy (the sun's noon height is 90 - |latitude - declination|) ──────────────────────

    @Test
    fun `at 40 degrees north the sun stands 73 and a half degrees high at noon on the June solstice`() {
        // 90 - 40 + 23.44 = 73.44; solar noon at longitude 0 is about 12:02 UTC then (equation of time near -2 min).
        val elevation = SolarClock.elevationDeg(lat = 40.0, lng = 0.0, epochMs = utcMs(2026, 6, 21, 12, 2))
        assertEquals(73.44, elevation, 0.3)
    }

    @Test
    fun `in Sao Paulo the sun is almost overhead at noon on the December solstice`() {
        // Declination -23.44 against latitude -23.55: 89.9 degrees. Solar noon at 46.63 W is about 15:05 UTC.
        val elevation = SolarClock.elevationDeg(lat = -23.55, lng = -46.63, epochMs = utcMs(2026, 12, 21, 15, 5))
        assertEquals(89.9, elevation, 0.6)
    }

    @Test
    fun `at 6 UTC on the equator the sun is at the horizon, a little below it, in March`() {
        // Solar time then is about 5:53 (equation of time -7 min): 1.8 degrees under the horizon.
        val elevation = SolarClock.elevationDeg(lat = 0.0, lng = 0.0, epochMs = utcMs(2026, 3, 20, 6))
        assertEquals(-1.85, elevation, 0.5)
    }

    @Test
    fun `the sun never sets at the pole in June and never rises in December`() {
        assertTrue(SolarClock.elevationDeg(80.0, 10.0, utcMs(2026, 6, 21, 0)) > 0.0)
        assertTrue(SolarClock.elevationDeg(80.0, 10.0, utcMs(2026, 12, 21, 12)) < 0.0)
    }

    // ── resolveIsLight() — deterministic hysteresis ─────────────────────────────────────────

    @Test
    fun `elevation above the light threshold is always light regardless of prior state`() {
        assertEquals(true, SolarClock.resolveIsLight(elevationDeg = 5.0, previouslyLight = false))
        assertEquals(true, SolarClock.resolveIsLight(elevationDeg = 5.0, previouslyLight = true))
    }

    @Test
    fun `elevation below the dark threshold is always dark regardless of prior state`() {
        assertEquals(false, SolarClock.resolveIsLight(elevationDeg = -10.0, previouslyLight = true))
        assertEquals(false, SolarClock.resolveIsLight(elevationDeg = -10.0, previouslyLight = false))
    }

    @Test
    fun `elevation inside the dead band keeps whatever the previous state was`() {
        assertEquals(true, SolarClock.resolveIsLight(elevationDeg = -3.0, previouslyLight = true))
        assertEquals(false, SolarClock.resolveIsLight(elevationDeg = -3.0, previouslyLight = false))
    }

    @Test
    fun `the boundaries themselves belong to their named side, not the dead band`() {
        // > 0 is light, so exactly 0.0 is NOT above threshold -> stays in dead-band behavior.
        assertEquals(true, SolarClock.resolveIsLight(elevationDeg = 0.1, previouslyLight = false))
        assertEquals(false, SolarClock.resolveIsLight(elevationDeg = -6.1, previouslyLight = true))
    }
}
