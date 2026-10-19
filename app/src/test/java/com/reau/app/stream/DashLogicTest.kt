// The stream rules: frame intervals, idle keyframes, drain wait, bitrate, actual fps.
package com.reau.app.stream

import com.reau.app.dash.DashConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashLogicTest {

    // ── frameIntervalsMsForFps (configurable stream FPS) ─────────────────────────────────────────

    @Test
    fun `frameIntervalsMsForFps at 4 reproduces today's exact fixed constants`() {
        assertEquals(250L to 500L, DashLogic.frameIntervalsMsForFps(4))
    }

    @Test
    fun `frameIntervalsMsForFps idle is double active or 250 ms, whichever is longer`() {
        for (fps in listOf(1, 2, 4, 6, 8, 12, 16, 20, 22, 24, 30)) {
            val (active, idle) = DashLogic.frameIntervalsMsForFps(fps)
            assertEquals("fps=$fps", maxOf(active * 2, 250L), idle)
        }
    }

    @Test
    fun `a still map never runs faster than 4 fps`() {
        for (fps in 2..30 step 2) {
            assertTrue("fps=$fps", DashLogic.frameIntervalsMsForFps(fps).second >= 250L)
        }
    }

    @Test
    fun `rates up to 8 fps keep today's exact idle interval`() {
        assertEquals(167L to 334L, DashLogic.frameIntervalsMsForFps(6))
        assertEquals(125L to 250L, DashLogic.frameIntervalsMsForFps(8))
    }

    @Test
    fun `frameIntervalsMsForFps at the extremes`() {
        assertEquals(1_000L to 2_000L, DashLogic.frameIntervalsMsForFps(1))
        assertEquals(83L to 250L, DashLogic.frameIntervalsMsForFps(12))
        assertEquals(45L to 250L, DashLogic.frameIntervalsMsForFps(22))
        assertEquals(33L to 250L, DashLogic.frameIntervalsMsForFps(30))
    }

    // ── idleSyncNeeded: keyframe requests while the map is still ─────────────────────────────────

    @Test
    fun `no extra keyframes when the encoder's own interval still lands within 2 s`() {
        assertFalse(DashLogic.idleSyncNeeded(hintFps = 4, keyframeIntervalSec = 1, idleFrameMs = 500))   // 2.0 s
        assertFalse(DashLogic.idleSyncNeeded(hintFps = 8, keyframeIntervalSec = 1, idleFrameMs = 250))   // 2.0 s
        assertFalse(DashLogic.idleSyncNeeded(hintFps = 6, keyframeIntervalSec = 1, idleFrameMs = 334))   // 2.0 s
    }

    @Test
    fun `extra keyframes once a high fps hint would space them past 2 s`() {
        assertTrue(DashLogic.idleSyncNeeded(hintFps = 12, keyframeIntervalSec = 1, idleFrameMs = 250))   // 3.0 s
        assertTrue(DashLogic.idleSyncNeeded(hintFps = 22, keyframeIntervalSec = 1, idleFrameMs = 250))   // 5.5 s
        assertTrue(DashLogic.idleSyncNeeded(hintFps = 8, keyframeIntervalSec = 2, idleFrameMs = 250))    // 4.0 s
    }

    @Test
    fun `frameIntervalsMsForFps never divides by zero or goes negative for a non-positive fps`() {
        val (active0, idle0) = DashLogic.frameIntervalsMsForFps(0)
        assertTrue(active0 > 0); assertTrue(idle0 > 0)
        val (activeNeg, idleNeg) = DashLogic.frameIntervalsMsForFps(-5)
        assertTrue(activeNeg > 0); assertTrue(idleNeg > 0)
    }

    @Test
    fun `frameIntervalsMsForFps is monotonically non-increasing as fps rises`() {
        val fpsValues = listOf(1, 4, 8, 12, 16, 20, 24, 30)
        val actives = fpsValues.map { DashLogic.frameIntervalsMsForFps(it).first }
        for (i in 1 until actives.size) assertTrue("fps=${fpsValues[i]}", actives[i] <= actives[i - 1])
    }

    // ── drainWaitUs: how long drain() waits for the frame just submitted ──────────────────────────

    @Test
    fun `drainWaitUs is half the frame interval, capped at 40 ms`() {
        assertEquals(40_000L, DashLogic.drainWaitUs(250))  // 4 fps
        assertEquals(40_000L, DashLogic.drainWaitUs(125))  // 8 fps
        assertEquals(40_000L, DashLogic.drainWaitUs(83))   // 12 fps: 41.5 ms -> cap
        assertEquals(22_500L, DashLogic.drainWaitUs(45))   // 22 fps
        assertEquals(16_500L, DashLogic.drainWaitUs(33))   // 30 fps
    }

    @Test
    fun `drainWaitUs never drops below the old fixed 10 ms`() {
        assertEquals(10_000L, DashLogic.drainWaitUs(10))
        assertEquals(10_000L, DashLogic.drainWaitUs(0))
        assertEquals(10_000L, DashLogic.drainWaitUs(-5))
    }

    // ── bitrate ceiling: one slider, 200..1024 kbps, is the encoder's
    // bitrate in both fixed and automatic fps; idle is half of it ─────────────────────────────────

    @Test
    fun `active bitrate is the chosen ceiling when it covers the fps, never above 1024 kbps`() {
        assertEquals(200_000, DashLogic.effectiveActiveBitrate(200_000, fps = 4))
        assertEquals(600_000, DashLogic.effectiveActiveBitrate(600_000, fps = 12))
        assertEquals(1_024_000, DashLogic.effectiveActiveBitrate(1_024_000, fps = 30))
        assertEquals(1_024_000, DashLogic.effectiveActiveBitrate(2_000_000, fps = 4))
    }

    @Test
    fun `automatic fps always uses the full 1024 kbps, whatever the slider says`() {
        for (bps in DashConfig.STREAM_BITRATE_STEPS) {
            assertEquals(1_024_000, DashLogic.bitrateCeiling(bps, autoFps = true))
        }
    }

    @Test
    fun `fixed fps uses the slider's ceiling`() {
        for (bps in DashConfig.STREAM_BITRATE_STEPS) {
            assertEquals(bps, DashLogic.bitrateCeiling(bps, autoFps = false))
        }
    }

    @Test
    fun `a fixed fps raises a ceiling that would starve its frames`() {
        assertEquals(1_024_000, DashLogic.effectiveActiveBitrate(200_000, fps = 30))
        assertEquals(500_000, DashLogic.effectiveActiveBitrate(300_000, fps = 12))
    }

    @Test
    fun `idle bitrate is half the active one`() {
        assertEquals(100_000, DashLogic.effectiveIdleBitrate(200_000, fps = 4))
        assertEquals(512_000, DashLogic.effectiveIdleBitrate(1_024_000, fps = 8))
        assertEquals(512_000, DashLogic.effectiveIdleBitrate(200_000, fps = 30))
    }

    @Test
    fun `minimum bitrate step for each fps keeps 34 kbit per frame`() {
        val expected = mapOf(
            4 to 200_000, 6 to 300_000, 8 to 300_000, 10 to 400_000, 12 to 500_000, 14 to 500_000,
            16 to 600_000, 18 to 700_000, 20 to 700_000, 22 to 800_000, 24 to 900_000, 26 to 900_000,
            28 to 1_024_000, 30 to 1_024_000,
        )
        for ((fps, bps) in expected) assertEquals("fps=$fps", bps, DashLogic.minBitrateForFps(fps))
    }

    @Test
    fun `fps and bitrate limits agree in both directions`() {
        for (fps in 4..30 step 2) {
            assertTrue("fps=$fps", DashLogic.maxAutoFpsForBitrate(DashLogic.minBitrateForFps(fps)) >= fps)
        }
        for (bps in DashConfig.STREAM_BITRATE_STEPS) {
            assertTrue("bps=$bps", DashLogic.minBitrateForFps(DashLogic.maxAutoFpsForBitrate(bps)) <= bps)
        }
    }

    @Test
    fun `automatic fps ceiling keeps at least 34 kbit per frame`() {
        assertEquals(30, DashLogic.maxAutoFpsForBitrate(1_024_000))
        assertEquals(26, DashLogic.maxAutoFpsForBitrate(900_000))
        assertEquals(22, DashLogic.maxAutoFpsForBitrate(800_000))
        assertEquals(20, DashLogic.maxAutoFpsForBitrate(700_000))
        assertEquals(16, DashLogic.maxAutoFpsForBitrate(600_000))
        assertEquals(14, DashLogic.maxAutoFpsForBitrate(500_000))
        assertEquals(10, DashLogic.maxAutoFpsForBitrate(400_000))
        assertEquals(8, DashLogic.maxAutoFpsForBitrate(300_000))
        assertEquals(4, DashLogic.maxAutoFpsForBitrate(200_000))
    }

    @Test
    fun `every offered step keeps its fps ceiling above the per-frame floor`() {
        for (bps in DashConfig.STREAM_BITRATE_STEPS) {
            val fps = DashLogic.maxAutoFpsForBitrate(bps)
            assertTrue("bps=$bps fps=$fps", fps == DashConfig.MIN_STREAM_FPS || bps / fps >= DashLogic.MIN_BITS_PER_FRAME)
        }
    }

    @Test
    fun `kbit per frame for the settings hint`() {
        assertEquals(34, DashLogic.kbitPerFrame(1_024_000, 30))
        assertEquals(128, DashLogic.kbitPerFrame(1_024_000, 8))
        assertEquals(50, DashLogic.kbitPerFrame(200_000, 4))
        assertEquals(0, DashLogic.kbitPerFrame(200_000, 0))
    }

    // ── computeActualFps ─────────────────────────────────────────────────

    @Test
    fun `computeActualFps matches the configured rate when frames arrive exactly on schedule`() {
        // 6fps for a 5s window -> 30 frames.
        assertEquals(6.0, DashLogic.computeActualFps(frames = 30, elapsedMs = 5_000), 1e-9)
    }

    @Test
    fun `computeActualFps reports below target when the pipeline falls behind`() {
        // Asked for 6fps but only 15 frames actually landed in the same 5s window.
        assertEquals(3.0, DashLogic.computeActualFps(frames = 15, elapsedMs = 5_000), 1e-9)
    }

    @Test
    fun `computeActualFps returns zero instead of dividing by a non-positive window`() {
        assertEquals(0.0, DashLogic.computeActualFps(frames = 30, elapsedMs = 0), 1e-9)
        assertEquals(0.0, DashLogic.computeActualFps(frames = 30, elapsedMs = -100), 1e-9)
    }

    @Test
    fun `computeActualFps handles zero frames without crashing`() {
        assertEquals(0.0, DashLogic.computeActualFps(frames = 0, elapsedMs = 5_000), 1e-9)
    }

}
