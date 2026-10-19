package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The maneuver profile's own logic, isolated from [StreamGovernor]: distance with hysteresis. */
class ManeuverProfileTest {
    @Test
    fun `no sample yet is null, and null stays null`() {
        val p = ManeuverProfile()
        assertNull(p.onSample(null))
    }

    @Test
    fun `near a maneuver is FULL at once`() {
        val p = ManeuverProfile()
        assertEquals(QualityTier.FULL, p.onSample(500))
        assertEquals(QualityTier.FULL, p.onSample(1))
    }

    @Test
    fun `far does not flip to REDUCED on the first sample, only once past the hysteresis`() {
        val p = ManeuverProfile()
        assertEquals("first far sample: sets the baseline, stays FULL", QualityTier.FULL, p.onSample(1200))
        assertEquals("still inside the 200 m margin", QualityTier.FULL, p.onSample(1399))
        assertEquals("1200 + 200: now REDUCED", QualityTier.REDUCED, p.onSample(1400))
    }

    @Test
    fun `riding towards a far maneuver turns REDUCED after 200 m`() {
        // 11 km to the maneuver, then 100 m steps down a long road.
        val p = ManeuverProfile()
        assertEquals(QualityTier.FULL, p.onSample(11_000))
        assertEquals(QualityTier.FULL, p.onSample(10_900))
        assertEquals("200 m nearer than where the far zone began", QualityTier.REDUCED, p.onSample(10_800))
        assertEquals("and stays so while far", QualityTier.REDUCED, p.onSample(2_200))
        assertEquals("near: FULL at once", QualityTier.FULL, p.onSample(900))
    }

    @Test
    fun `oscillating just under 1000 m never leaves FULL`() {
        val p = ManeuverProfile()
        repeat(5) {
            assertEquals(QualityTier.FULL, p.onSample(999))
            assertEquals(QualityTier.FULL, p.onSample(950))
        }
    }

    @Test
    fun `dropping back under 1000 m returns to FULL at once and resets the far baseline`() {
        val p = ManeuverProfile()
        p.onSample(1200)
        assertEquals(QualityTier.REDUCED, p.onSample(1450))
        assertEquals(QualityTier.FULL, p.onSample(800))
        // A fresh far baseline: 1100 alone (900 away from nothing yet) is not 200 m past anything.
        assertEquals(QualityTier.FULL, p.onSample(1100))
    }
}

/** The speed profile's own logic, isolated from [StreamGovernor]: a classic hysteresis band. */
class SpeedProfileTest {
    @Test
    fun `no sample yet is null, and null stays null`() {
        val p = SpeedProfile()
        assertNull(p.onSample(null))
    }

    @Test
    fun `at or under the low threshold is FULL`() {
        val p = SpeedProfile()
        assertEquals(QualityTier.FULL, p.onSample(0.0))
        assertEquals(QualityTier.FULL, p.onSample(55.0))
    }

    @Test
    fun `at or over the high threshold is REDUCED`() {
        val p = SpeedProfile()
        assertEquals(QualityTier.REDUCED, p.onSample(65.0))
        assertEquals(QualityTier.REDUCED, p.onSample(120.0))
    }

    @Test
    fun `inside the dead band keeps whatever it already was`() {
        val p = SpeedProfile()
        assertEquals("starts FULL with no sample yet", QualityTier.FULL, p.onSample(60.0))
        p.onSample(65.0) // now REDUCED
        assertEquals(QualityTier.REDUCED, p.onSample(60.0))
    }
}
