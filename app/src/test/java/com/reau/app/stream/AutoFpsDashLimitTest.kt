package com.reau.app.stream

import com.reau.app.dash.DashConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic fps must not outrun the dash. As ride logs showed, the dash does not acknowledge frames one by
 * one, so the controller only saw that the PHONE delivered every frame; once the frame loop stopped waiting for the
 * encoder the phone delivered anything asked of it, the rate climbed
 * 20 → 30 fps in 80 seconds, and at 30 the dash went silent with its Wi-Fi still up. Every reconnection then started
 * straight at the remembered 30 and the dash never decoded a frame: 21 drops in 7 minutes, then 15 more.
 *
 * Three rules came out of that, tested here: a ceiling at what the dash has been seen to hold; a stream the dash
 * stopped answering while it stayed reachable restarts far lower and lowers the ceiling; and the full rate only
 * starts once the dash has confirmed the first frame.
 */
class AutoFpsDashLimitTest {
    private var now = 0L

    private fun AutoFpsController.healthyWindow(): AutoFpsController.Decision {
        now += 5_000
        val fps = currentFps
        return onWindow(AutoFpsController.Window(fps, fps.toDouble(), 0.0, 150.0, allActive = true, thermalOk = true), now)
    }

    // ── the ceiling ────────────────────────────────────────────────────────────

    @Test
    fun `with nothing but the phone's own delivery to go by, it stops at the ceiling`() {
        // Exactly the windows of that ride: sent = target, dash = 0,0 (it does not ack frames).
        val c = AutoFpsController(8, ceiling = 16)
        val reached = (1..200).map { c.healthyWindow().fps }
        assertEquals(16, reached.max())
        assertEquals(16, c.currentFps)
        assertTrue(c.healthyWindow().reason.contains("at ceiling"))
    }

    @Test
    fun `a remembered rate above the ceiling starts at the ceiling`() {
        // What was stored on the phone after that ride: 30.
        assertEquals(16, AutoFpsController(30, ceiling = 16).currentFps)
    }

    @Test
    fun `a ceiling between two steps is the step below it`() {
        assertEquals(14, AutoFpsController(30, ceiling = 15).currentFps)
    }

    @Test
    fun `without a ceiling given it is the slider's own top, as before`() {
        assertEquals(DashConfig.MAX_STREAM_FPS, AutoFpsController(99).currentFps)
    }

    @Test
    fun `the ceiling the app runs with is the highest rate the dash was seen to hold for minutes`() {
        // 16 fps held for minutes without a drop. Above it the stretches were much shorter
        // (about a minute at 18 fps); 30 fps killed the stream.
        assertEquals(16, DashConfig.AUTO_FPS_CEILING)
    }

    // ── starting a stream ──────────────────────────────────────────────────────

    @Test
    fun `a normal start is the remembered rate, under the ceiling`() {
        val plan = AutoFpsController.plan(storedStable = 12, dashLimit = null, failedFps = null)
        assertEquals(AutoFpsController.Plan(startFps = 12, ceiling = 16, dashLimit = null), plan)
    }

    @Test
    fun `the 30 remembered from before the ceiling existed starts at 16`() {
        assertEquals(16, AutoFpsController.plan(storedStable = 30, dashLimit = null, failedFps = null).startFps)
    }

    @Test
    fun `after the dash stopped answering at a rate, the next stream starts at half of it`() {
        // The loop seen on that ride: failed at 30, restarted at 30, 21 times.
        val plan = AutoFpsController.plan(storedStable = 30, dashLimit = null, failedFps = 30)
        assertEquals(14, plan.startFps)
        assertEquals("the failed rate itself is out", 28, plan.dashLimit)
        assertEquals("still never above what the dash is known to hold", 16, plan.ceiling)
    }

    @Test
    fun `a failure at the ceiling lowers the ceiling one step and restarts at half`() {
        val plan = AutoFpsController.plan(storedStable = 16, dashLimit = null, failedFps = 16)
        assertEquals(AutoFpsController.Plan(startFps = 8, ceiling = 14, dashLimit = 14), plan)
    }

    @Test
    fun `a second failure lowers it again, never below the floor`() {
        assertEquals(AutoFpsController.Plan(startFps = 6, ceiling = 12, dashLimit = 12), AutoFpsController.plan(14, dashLimit = 14, failedFps = 14))
        val atTheFloor = AutoFpsController.plan(storedStable = 4, dashLimit = 4, failedFps = 4)
        assertEquals(AutoFpsController.Plan(startFps = DashConfig.MIN_STREAM_FPS, ceiling = DashConfig.MIN_STREAM_FPS, dashLimit = DashConfig.MIN_STREAM_FPS), atTheFloor)
    }

    @Test
    fun `a limit learned earlier holds the next normal start too`() {
        val plan = AutoFpsController.plan(storedStable = 16, dashLimit = 12, failedFps = null)
        assertEquals(AutoFpsController.Plan(startFps = 12, ceiling = 12, dashLimit = 12), plan)
    }

    @Test
    fun `a failure above an earlier limit does not raise it`() {
        assertEquals(10, AutoFpsController.plan(storedStable = 10, dashLimit = 10, failedFps = 14).dashLimit)
    }

    // ── what counts as the dash failing, and how long a limit lasts ─────────────

    @Test
    fun `a stream is back within half a minute only if the dash was there all along`() {
        // Ignition off: the dash's Wi-Fi is gone and nothing reconnects. The dash choking: it authenticates again in
        // two seconds. Only the second says something about the rate.
        assertTrue(DashLogic.dashFailedWhileReachable(watchdogFailMs = 1_000_000L, streamStartMs = 1_005_000L))
        assertTrue(DashLogic.dashFailedWhileReachable(watchdogFailMs = 1_000_000L, streamStartMs = 1_030_000L))
        assertFalse(DashLogic.dashFailedWhileReachable(watchdogFailMs = 1_000_000L, streamStartMs = 1_030_001L))
        assertFalse("no watchdog ever fired", DashLogic.dashFailedWhileReachable(watchdogFailMs = 0L, streamStartMs = 1_005_000L))
        // Zero means "never", whatever the clock and whatever was counted before: it is not a failure at time zero.
        assertFalse(DashLogic.dashFailedWhileReachable(watchdogFailMs = 0L, streamStartMs = 5_000L, countedFailMs = 7L))
    }

    @Test
    fun `one failure is counted once, however many streams start right after it`() {
        // A second reconnection inside the same half minute would otherwise halve the rate again for the same drop.
        assertTrue(DashLogic.dashFailedWhileReachable(watchdogFailMs = 1_000_000L, streamStartMs = 1_005_000L, countedFailMs = 0L))
        assertFalse(DashLogic.dashFailedWhileReachable(watchdogFailMs = 1_000_000L, streamStartMs = 1_020_000L, countedFailMs = 1_000_000L))
        assertTrue("a newer failure counts", DashLogic.dashFailedWhileReachable(watchdogFailMs = 1_040_000L, streamStartMs = 1_045_000L, countedFailMs = 1_000_000L))
    }

    @Test
    fun `a learned limit is forgotten after a day`() {
        // So one bad ride (or a false alarm) does not hold the rate down for good.
        val day = 24 * 60 * 60 * 1000L
        assertEquals(12, DashLogic.liveDashLimit(limit = 12, learnedAtMs = 5_000L, nowMs = 5_000L + day - 1))
        assertNull(DashLogic.liveDashLimit(limit = 12, learnedAtMs = 5_000L, nowMs = 5_000L + day))
        assertNull("none stored", DashLogic.liveDashLimit(limit = 0, learnedAtMs = 5_000L, nowMs = 6_000L))
    }

    // ── the first frame ────────────────────────────────────────────────────────

    @Test
    fun `until the dash confirms the first frame the stream stays at the still-map pace`() {
        // On that ride every stream that began at 4 fps (map still) had its first frame confirmed in about 8 s; of the
        // 36 that began at 30 fps in motion, none was.
        assertFalse(DashLogic.fullRate(mapChangedRecently = true, firstFrameConfirmed = false))
        assertTrue(DashLogic.fullRate(mapChangedRecently = true, firstFrameConfirmed = true))
        assertFalse(DashLogic.fullRate(mapChangedRecently = false, firstFrameConfirmed = true))
        assertFalse(DashLogic.fullRate(mapChangedRecently = false, firstFrameConfirmed = false))
    }
}
