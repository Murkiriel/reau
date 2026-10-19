package com.reau.app.stream

import com.reau.app.dash.DashConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rider's own ceiling for automatic fps: automatic is always on, and the fps
 * control in Settings says how high it may climb. It is how the dash's real limit gets found on the bike — the dash
 * does not acknowledge frames, so the app cannot find it alone: the rider picks 18, 20, 24..., rides, and the rules
 * of AutoFpsDashLimitTest (restart at half after the dash stops answering, the failed rate ruled out) bound the
 * damage of a ceiling set too high.
 *
 * Also here: the test switch that brings back the older frame loop, which waited for the encoder.
 */
class AutoFpsRiderCeilingTest {
    private var now = 0L

    private fun AutoFpsController.healthyWindow(): AutoFpsController.Decision {
        now += 5_000
        val fps = currentFps
        return onWindow(AutoFpsController.Window(fps, fps.toDouble(), 0.0, 150.0, allActive = true, thermalOk = true), now)
    }

    // ── the rider's ceiling ────────────────────────────────────────────────────

    @Test
    fun `the rider's ceiling is where the climb stops, above or below the default`() {
        assertEquals(AutoFpsController.Plan(startFps = 12, ceiling = 24, dashLimit = null), AutoFpsController.plan(12, null, null, riderCeiling = 24))
        assertEquals(AutoFpsController.Plan(startFps = 10, ceiling = 10, dashLimit = null), AutoFpsController.plan(12, null, null, riderCeiling = 10))
    }

    @Test
    fun `asked for 30, the controller climbs to 30 and holds`() {
        val plan = AutoFpsController.plan(storedStable = 16, dashLimit = null, failedFps = null, riderCeiling = 30)
        val c = AutoFpsController(plan.startFps, plan.ceiling)
        repeat(200) { c.healthyWindow() }
        assertEquals(30, c.currentFps)
    }

    @Test
    fun `without a ceiling from the rider it is the default one`() {
        assertEquals(DashConfig.AUTO_FPS_CEILING, AutoFpsController.plan(30, null, null).ceiling)
    }

    @Test
    fun `a rate the dash failed at holds even a higher ceiling from the rider`() {
        // Ceiling 30, the dash stopped answering at 30: back at half, and 28 is as high as it goes until the rider
        // moves the control again (which forgets the limit).
        assertEquals(AutoFpsController.Plan(startFps = 14, ceiling = 28, dashLimit = 28), AutoFpsController.plan(30, null, failedFps = 30, riderCeiling = 30))
        assertEquals(AutoFpsController.Plan(startFps = 16, ceiling = 28, dashLimit = 28), AutoFpsController.plan(16, dashLimit = 28, failedFps = null, riderCeiling = 30))
    }

    @Test
    fun `a ceiling from the rider below the learned limit is the one that counts`() {
        assertEquals(AutoFpsController.Plan(startFps = 12, ceiling = 12, dashLimit = 28), AutoFpsController.plan(20, dashLimit = 28, failedFps = null, riderCeiling = 12))
    }

    @Test
    fun `the controller says what its ceiling is, so a change of the control is noticed mid-ride`() {
        assertEquals(24, AutoFpsController(8, ceiling = 24).ceiling)
        assertEquals(14, AutoFpsController(8, ceiling = 15).ceiling)
    }

    @Test
    fun `a ceiling lowered mid-ride brings the rate down at once, and one raised lets it climb from where it was`() {
        val lowered = AutoFpsController.plan(storedStable = 24, dashLimit = null, failedFps = null, riderCeiling = 16)
        assertEquals(16, AutoFpsController(lowered.startFps, lowered.ceiling).currentFps)
        val raised = AutoFpsController.plan(storedStable = 16, dashLimit = null, failedFps = null, riderCeiling = 24)
        val c = AutoFpsController(raised.startFps, raised.ceiling)
        assertEquals("no jump: it starts from the rate it was at", 16, c.currentFps)
        repeat(50) { c.healthyWindow() }
        assertEquals(24, c.currentFps)
    }

    // ── waiting for the encoder (the older loop), as a test switch ───────────────────────────────

    @Test
    fun `the encoder is rebuilt when the rate, the keyframe interval or the way its output is read changes`() {
        assertFalse(DashLogic.encoderRebuildNeeded(fps = 16, encoderFps = 16, keyframeSec = 1, encoderKeyframeSec = 1, waits = false, encoderWaits = false))
        assertTrue(DashLogic.encoderRebuildNeeded(fps = 18, encoderFps = 16, keyframeSec = 1, encoderKeyframeSec = 1, waits = false, encoderWaits = false))
        assertTrue(DashLogic.encoderRebuildNeeded(fps = 16, encoderFps = 16, keyframeSec = 2, encoderKeyframeSec = 1, waits = false, encoderWaits = false))
        assertTrue("the switch was flipped", DashLogic.encoderRebuildNeeded(fps = 16, encoderFps = 16, keyframeSec = 1, encoderKeyframeSec = 1, waits = true, encoderWaits = false))
        assertTrue(DashLogic.encoderRebuildNeeded(fps = 16, encoderFps = 16, keyframeSec = 1, encoderKeyframeSec = 1, waits = false, encoderWaits = true))
    }
}
