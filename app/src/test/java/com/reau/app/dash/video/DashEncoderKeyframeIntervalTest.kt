package com.reau.app.dash.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the ride log says what `KEY_I_FRAME_INTERVAL` was asked for (plan 22, item 1). */
class DashEncoderKeyframeIntervalTest {
    @Test
    fun `the log line says the seconds asked for and the frames the encoder counts at the hint`() {
        assertEquals("1 (every 4 frames at the hint)", DashEncoder.iFrameIntervalText(1, fpsHint = 4))
        assertEquals("2 (every 8 frames at the hint)", DashEncoder.iFrameIntervalText(2, fpsHint = 4))
        assertEquals("1 (every 16 frames at the hint)", DashEncoder.iFrameIntervalText(1, fpsHint = 16))
    }
}
