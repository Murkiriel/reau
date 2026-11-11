package com.reau.app.stream

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the rider's settings decide of the stream, as the video loop receives it ([VideoLoop.Settings.withRider]): the three experimental
 * switches (spaced RTP packets, the bitrate that follows the encoder, the mute encoder watch) go from the settings to the loop, and the
 * bitrate is the effective one. A projection starts with it and a new stream takes it up again. It used to be read from the source of the service.
 */
class VideoLoopSettingsTest {
    private val loop = VideoLoop.Settings()

    @Test
    fun `with the experiments off nothing of them reaches the loop`() {
        val s = loop.withRider(REauSettings())
        assertFalse(s.rtpPacing)
        assertFalse(s.adaptiveBitrate)
        assertFalse(s.encoderMuteWatch)
    }

    @Test
    fun `each experimental switch reaches the loop alone`() {
        val pacing = loop.withRider(REauSettings(experimentRtpPacing = true))
        assertEquals(listOf(true, false, false), listOf(pacing.rtpPacing, pacing.adaptiveBitrate, pacing.encoderMuteWatch))
        val adaptive = loop.withRider(REauSettings(experimentAdaptiveBitrate = true))
        assertEquals(listOf(false, true, false), listOf(adaptive.rtpPacing, adaptive.adaptiveBitrate, adaptive.encoderMuteWatch))
        val watch = loop.withRider(REauSettings(experimentEncoderWatch = true))
        assertEquals(listOf(false, false, true), listOf(watch.rtpPacing, watch.adaptiveBitrate, watch.encoderMuteWatch))
    }

    @Test
    fun `the bitrate is the rider's, or the experiment's target when the adaptive switch is on`() {
        assertEquals(400_000, loop.withRider(REauSettings(streamBitrateBps = 400_000)).bitrateBps)
        assertEquals(DashConfig.EXPERIMENT_TARGET_BPS, loop.withRider(REauSettings(streamBitrateBps = 400_000, experimentAdaptiveBitrate = true)).bitrateBps)
    }

    @Test
    fun `the pace, the clock, the mode and the keyframe interval are the rider's`() {
        val s = loop.withRider(REauSettings(fpsCeiling = 3, clockSlowPercent = 2, bitrateMode = BitrateMode.VBR, keyframeSec = 2))
        assertEquals(3, s.ceilingFps)
        assertEquals(2, s.clockSlowPercent)
        assertEquals(BitrateMode.VBR, s.bitrateMode)
        assertEquals(2, s.keyframeSec)
        assertEquals(REauSettings.lightScreenFps(), s.lightScreenFps)
    }

    @Test
    fun `what is not the rider's is left as it was, the recording among it`() {
        val dir = File("somewhere")
        val s = VideoLoop.Settings(recordFrames = 600, recordDir = dir, recordRolling = true, startFps = 3, windowMs = 1_234L).withRider(REauSettings())
        assertEquals(600, s.recordFrames)
        assertEquals(dir, s.recordDir)
        assertTrue(s.recordRolling)
        assertEquals(3, s.startFps)
        assertEquals(1_234L, s.windowMs)
    }
}
