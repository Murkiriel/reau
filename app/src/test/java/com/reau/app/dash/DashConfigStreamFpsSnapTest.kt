package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class DashConfigStreamFpsSnapTest {

    @Test
    fun `range is 4 to 30 in steps of 2 with a 1024 kbps ceiling`() {
        assertEquals(4, DashConfig.MIN_STREAM_FPS)
        assertEquals(30, DashConfig.MAX_STREAM_FPS)
        assertEquals(2, DashConfig.STREAM_FPS_STEP)
        assertEquals(1_024_000, DashConfig.MAX_STREAM_BITRATE_BPS)
    }

    @Test
    fun `every even value in range is kept as is`() {
        for (fps in 4..30 step 2) assertEquals(fps, DashConfig.snapStreamFps(fps))
    }

    @Test
    fun `odd values round down to even`() {
        assertEquals(6, DashConfig.snapStreamFps(7))
        assertEquals(28, DashConfig.snapStreamFps(29))
        assertEquals(4, DashConfig.snapStreamFps(5))
    }

    @Test
    fun `out of range values clamp, including the removed 2 fps`() {
        assertEquals(4, DashConfig.snapStreamFps(2))
        assertEquals(4, DashConfig.snapStreamFps(3))
        assertEquals(4, DashConfig.snapStreamFps(0))
        assertEquals(4, DashConfig.snapStreamFps(-5))
        assertEquals(30, DashConfig.snapStreamFps(31))
        assertEquals(30, DashConfig.snapStreamFps(999))
    }

    @Test
    fun `defaults are valid even values`() {
        assertEquals(DashConfig.DEFAULT_STREAM_FPS, DashConfig.snapStreamFps(DashConfig.DEFAULT_STREAM_FPS))
        assertEquals(DashConfig.AUTO_FPS_START, DashConfig.snapStreamFps(DashConfig.AUTO_FPS_START))
    }

    @Test
    fun `bitrate steps are 200 to 900 by 100, then 1024`() {
        assertEquals(
            listOf(200_000, 300_000, 400_000, 500_000, 600_000, 700_000, 800_000, 900_000, 1_024_000),
            DashConfig.STREAM_BITRATE_STEPS,
        )
        assertEquals(1_024_000, DashConfig.DEFAULT_STREAM_BITRATE_BPS)
    }

    @Test
    fun `stored bitrates snap to the nearest step`() {
        for (bps in DashConfig.STREAM_BITRATE_STEPS) assertEquals(bps, DashConfig.snapStreamBitrate(bps))
        assertEquals(200_000, DashConfig.snapStreamBitrate(50_000))
        assertEquals(200_000, DashConfig.snapStreamBitrate(150_000))
        assertEquals(400_000, DashConfig.snapStreamBitrate(450_000))
        assertEquals(1_024_000, DashConfig.snapStreamBitrate(1_000_000))
        assertEquals(1_024_000, DashConfig.snapStreamBitrate(5_000_000))
    }

    @Test
    fun `keyframe interval offers automatic, 1 or 2 seconds, automatic by default`() {
        assertEquals(listOf(DashConfig.KEYFRAME_INTERVAL_AUTO, 1, 2), DashConfig.KEYFRAME_INTERVAL_OPTIONS)
        assertEquals(0, DashConfig.KEYFRAME_INTERVAL_AUTO)
        assertEquals(DashConfig.KEYFRAME_INTERVAL_AUTO, DashConfig.DEFAULT_KEYFRAME_INTERVAL_SEC)
    }

    @Test
    fun `older stored keyframe intervals snap into the offered set`() {
        assertEquals(0, DashConfig.snapKeyframeIntervalSec(0))
        assertEquals(1, DashConfig.snapKeyframeIntervalSec(1))
        assertEquals(2, DashConfig.snapKeyframeIntervalSec(2))
        assertEquals(2, DashConfig.snapKeyframeIntervalSec(3))
        assertEquals(2, DashConfig.snapKeyframeIntervalSec(5))
        assertEquals(0, DashConfig.snapKeyframeIntervalSec(-1))
    }

    @Test
    fun `the old automatic bitrate sentinel becomes the 1024 ceiling`() {
        assertEquals(1_024_000, DashConfig.snapStreamBitrate(-1))
        assertEquals(1_024_000, DashConfig.snapStreamBitrate(0))
    }
}
