package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the video decoder decides about each picture it is given, with no MediaCodec (the codec itself is VideoDecoderTest's, on a device). */
class VideoDecoderRulesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `a picture that starts with an SPS or a PPS is codec config, with a four or a three byte start code`() {
        val sps4 = bytes(0, 0, 0, 1, 0x67, 1, 2, 3)
        val pps4 = bytes(0, 0, 0, 1, 0x68, 1, 2, 3)
        val sps3 = bytes(0, 0, 1, 0x67, 1, 2, 3, 4)
        val idr = bytes(0, 0, 0, 1, 0x65, 1, 2, 3)
        val slice = bytes(0, 0, 0, 1, 0x41, 1, 2, 3)
        assertTrue(VideoDecoder.isCodecConfigData(sps4, 0, sps4.size))
        assertTrue(VideoDecoder.isCodecConfigData(pps4, 0, pps4.size))
        assertTrue(VideoDecoder.isCodecConfigData(sps3, 0, sps3.size))
        assertFalse("an IDR is a picture", VideoDecoder.isCodecConfigData(idr, 0, idr.size))
        assertFalse("a P slice", VideoDecoder.isCodecConfigData(slice, 0, slice.size))
    }

    @Test
    fun `the search for the start code takes the offset, stops at the first one and gives up after 32 bytes`() {
        val behind = bytes(9, 9, 0, 0, 0, 1, 0x67, 5, 5, 5)
        assertTrue("the picture starts at offset 2", VideoDecoder.isCodecConfigData(behind, 2, 8))
        assertFalse("seen from offset 0 the first bytes are no start code", VideoDecoder.isCodecConfigData(behind, 0, 2))
        val late = ByteArray(40) { 7 } + bytes(0, 0, 0, 1, 0x67, 0, 0)
        assertFalse("a start code after 32 bytes is not looked for", VideoDecoder.isCodecConfigData(late, 0, late.size))
        val within = ByteArray(20) { 7 } + bytes(0, 0, 0, 1, 0x67, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue("one inside the first 32 bytes is", VideoDecoder.isCodecConfigData(within, 0, within.size))
        val first = bytes(0, 0, 0, 1, 0x41, 0, 0, 0, 1, 0x67, 0, 0, 0)
        assertFalse("the first NAL decides, not a later one", VideoDecoder.isCodecConfigData(first, 0, first.size))
    }

    @Test
    fun `a picture too short for a start code and a NAL header is no config, and neither is a start code with nothing after it`() {
        assertFalse(VideoDecoder.isCodecConfigData(bytes(0, 0, 0, 1), 0, 4))
        assertFalse(VideoDecoder.isCodecConfigData(bytes(0, 0, 0, 1, 0x67), 0, 4))
        assertTrue("five bytes are enough", VideoDecoder.isCodecConfigData(bytes(0, 0, 0, 1, 0x67), 0, 5))
        assertFalse(VideoDecoder.isCodecConfigData(bytes(0, 0, 1, 0x67), 0, 4))
        val cut = bytes(1, 2, 3, 0, 0, 0, 1)
        assertFalse("the NAL header would be past the end", VideoDecoder.isCodecConfigData(cut, 0, cut.size))
    }

    @Test
    fun `some chips want every parameter set flagged as config, known by the codec's name`() {
        assertTrue(VideoDecoder.alwaysFlagsConfig("OMX.rk.video_decoder.avc"))
        assertTrue(VideoDecoder.alwaysFlagsConfig("OMX.allwinner.video.decoder.avc"))
        assertTrue(VideoDecoder.alwaysFlagsConfig("OMX.TCC.video.decoder"))
        assertTrue("lower and upper case are the same", VideoDecoder.alwaysFlagsConfig("OMX.RK.VIDEO_DECODER.AVC"))
        assertFalse(VideoDecoder.alwaysFlagsConfig("c2.qti.avc.decoder"))
        assertFalse(VideoDecoder.alwaysFlagsConfig("c2.android.avc.decoder"))
        assertFalse("rk inside another word is not the chip", VideoDecoder.alwaysFlagsConfig("OMX.mark.avc"))
        assertFalse("no codec yet", VideoDecoder.alwaysFlagsConfig(null))
    }

    @Test
    fun `a buffer is flagged as config when it holds the parameter sets and the codec wants them or has none yet`() {
        assertTrue(VideoDecoder.flagsAsConfig(isConfig = true, alwaysFlags = false, configured = false))
        assertTrue(VideoDecoder.flagsAsConfig(isConfig = true, alwaysFlags = true, configured = true))
        assertFalse("configured already and no special chip", VideoDecoder.flagsAsConfig(isConfig = true, alwaysFlags = false, configured = true))
        assertFalse("a picture is never config", VideoDecoder.flagsAsConfig(isConfig = false, alwaysFlags = true, configured = false))
        assertFalse(VideoDecoder.flagsAsConfig(isConfig = false, alwaysFlags = false, configured = false))
    }

    @Test
    fun `a picture is dropped whole only when it is bigger than the input buffer`() {
        assertFalse(VideoDecoder.tooBig(remaining = 100, capacity = 100))
        assertTrue(VideoDecoder.tooBig(remaining = 101, capacity = 100))
        assertFalse(VideoDecoder.tooBig(remaining = 0, capacity = 100))
    }

    @Test
    fun `the pictures a second are counted over a window of a second or more`() {
        assertFalse(VideoDecoder.fpsWindowOver(999))
        assertTrue(VideoDecoder.fpsWindowOver(1_000))
        assertEquals(4, VideoDecoder.fpsOf(frames = 4, elapsedMs = 1_000))
        assertEquals(3, VideoDecoder.fpsOf(frames = 4, elapsedMs = 1_300))
        assertEquals(8, VideoDecoder.fpsOf(frames = 4, elapsedMs = 500))
    }

    @Test
    fun `the decoder restarts from the third exception in a row, not the second`() {
        assertFalse(VideoDecoder.restartAfterErrors(1))
        assertFalse(VideoDecoder.restartAfterErrors(2))
        assertTrue(VideoDecoder.restartAfterErrors(3))
        assertTrue(VideoDecoder.restartAfterErrors(4))
    }

    @Test
    fun `a codec that starts after dropped pictures is mid-chain unless it starts on a keyframe`() {
        assertFalse("nothing was dropped: the first picture starts the codec", VideoDecoder.startedMidChain(dropped = 0, startsOnKeyframe = false))
        assertTrue(VideoDecoder.startedMidChain(dropped = 3, startsOnKeyframe = false))
        assertFalse("it starts on a keyframe: nothing is missing", VideoDecoder.startedMidChain(dropped = 3, startsOnKeyframe = true))
    }
}
