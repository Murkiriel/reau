package com.reau.app.dash.video

import android.media.MediaCodecInfo.EncoderCapabilities
import com.reau.app.dash.BitrateMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bitrate mode the rider picks (constant by default, to cut the bursts the dash lags and breaks on), checked
 * against what the phone's encoder says it offers.
 */
class DashEncoderRateModeTest {

    @Test
    fun `each mode is the encoder's own constant when it is offered`() {
        assertEquals(DashEncoder.RateMode(EncoderCapabilities.BITRATE_MODE_CBR, "CBR"), DashEncoder.rateMode(BitrateMode.CBR) { true })
        assertEquals(DashEncoder.RateMode(EncoderCapabilities.BITRATE_MODE_VBR, "VBR"), DashEncoder.rateMode(BitrateMode.VBR) { true })
    }

    @Test
    fun `none asked leaves the codec's own default, as before`() {
        assertEquals(DashEncoder.RateMode(null, "default"), DashEncoder.rateMode(null) { true })
    }

    @Test
    fun `a mode the encoder does not offer falls back to the other, then to the default`() {
        assertEquals(
            DashEncoder.RateMode(EncoderCapabilities.BITRATE_MODE_VBR, "VBR (CBR not offered)"),
            DashEncoder.rateMode(BitrateMode.CBR) { it == EncoderCapabilities.BITRATE_MODE_VBR },
        )
        assertEquals(DashEncoder.RateMode(null, "default (CBR not offered)"), DashEncoder.rateMode(BitrateMode.CBR) { false })
    }

    @Test
    fun `an encoder whose offer cannot be read is asked anyway (a refusal falls back when it is configured)`() {
        assertEquals(DashEncoder.RateMode(EncoderCapabilities.BITRATE_MODE_CBR, "CBR"), DashEncoder.rateMode(BitrateMode.CBR) { null })
    }
}
