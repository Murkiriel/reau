package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame ceiling a hot phone streams under. After OpenCfMoto's AdaptivePolicy.thermalFpsCap:
 * the phone's thermal status (PowerManager.THERMAL_STATUS_*: 0 none … 6 shutdown) lowers the ceiling, never above the
 * rider's own. OpenCfMoto's caps are absolute (20, 15, 12 for a 30 fps base); here the dash runs far below that, so
 * the same steps are fractions of the rider's ceiling: three quarters, five eighths, a half, on even rates, never
 * under the dash's 4 fps floor.
 */
class ThermalCeilingTest {

    @Test
    fun `a cool phone streams at the rider's ceiling`() {
        assertEquals(16, ThermalCeiling.capFor(status = 0, ceiling = 16)) // NONE
        assertEquals(16, ThermalCeiling.capFor(status = 1, ceiling = 16)) // LIGHT
        assertEquals("an unknown, negative status counts as cool", 16, ThermalCeiling.capFor(status = -1, ceiling = 16))
    }

    @Test
    fun `at the default ceiling of 16 the steps are 12, 10 and 8`() {
        assertEquals(12, ThermalCeiling.capFor(status = 2, ceiling = 16)) // MODERATE
        assertEquals(10, ThermalCeiling.capFor(status = 3, ceiling = 16)) // SEVERE
        assertEquals(8, ThermalCeiling.capFor(status = 4, ceiling = 16)) // CRITICAL
        assertEquals(8, ThermalCeiling.capFor(status = 5, ceiling = 16)) // EMERGENCY
        assertEquals(8, ThermalCeiling.capFor(status = 6, ceiling = 16)) // SHUTDOWN
    }

    @Test
    fun `at 30 the same fractions, on even rates rounded down`() {
        assertEquals(30, ThermalCeiling.capFor(status = 1, ceiling = 30))
        assertEquals(22, ThermalCeiling.capFor(status = 2, ceiling = 30)) // 22.5
        assertEquals(18, ThermalCeiling.capFor(status = 3, ceiling = 30)) // 18.75
        assertEquals(14, ThermalCeiling.capFor(status = 4, ceiling = 30)) // 15
    }

    @Test
    fun `never under the floor, never over the ceiling`() {
        for (status in 0..6) {
            assertEquals("ceiling 4, status $status", 4, ThermalCeiling.capFor(status, ceiling = 4))
        }
        assertEquals(4, ThermalCeiling.capFor(status = 2, ceiling = 6))
        for (ceiling in 4..30) {
            for (status in 0..6) {
                val cap = ThermalCeiling.capFor(status, ceiling)
                assertTrue("ceiling $ceiling, status $status: $cap", cap in 4..ceiling && cap % 2 == 0)
            }
        }
    }

    @Test
    fun `the hotter, never the faster`() {
        for (ceiling in 4..30) {
            val caps = (0..6).map { ThermalCeiling.capFor(it, ceiling) }
            assertEquals("ceiling $ceiling: $caps", caps.sortedDescending(), caps)
        }
    }

    @Test
    fun `an odd ceiling is taken as the even rate below it`() {
        assertEquals(14, ThermalCeiling.capFor(status = 0, ceiling = 15))
        // Written after the code, for the mutation run: the fractions are of the even rate, 28 (5/8 is 17.5 -> 16).
        assertEquals(16, ThermalCeiling.capFor(status = 3, ceiling = 29))
    }

    @Test
    fun `a rate within the hot ceiling is fine, one above it is not`() {
        assertTrue(ThermalCeiling.allows(status = 2, fps = 12, ceiling = 16))
        assertTrue(ThermalCeiling.allows(status = 2, fps = 10, ceiling = 16))
        assertFalse(ThermalCeiling.allows(status = 2, fps = 14, ceiling = 16))
        assertTrue(ThermalCeiling.allows(status = 0, fps = 16, ceiling = 16))
    }

    @Test
    fun `each status has a name for the ride log`() {
        assertEquals(
            listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown", "?7"),
            (0..7).map(ThermalCeiling::nameOf),
        )
    }
}
