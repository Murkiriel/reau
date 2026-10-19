package com.reau.app.video

import android.media.MediaCodecList
import com.reau.app.aa.VideoDecoder
import com.reau.app.util.CodecNames
import org.junit.rules.ExternalResource

/**
 * For the tests that read the decoded pictures back through an ImageReader: makes the app's video decoder use the
 * platform's software H.264 decoder instead of the device's hardware one. Some hardware decoders
 * hand an ImageReader images whose planes have no memory a test can read, and reading them aborts the
 * whole test process ("JNI DETECTED ERROR: non-zero capacity for nullptr pointer" in Image_createSurfacePlanes). What these tests
 * prove (the decoder's handling of the stream, the picture that comes out) does not depend on which decoder runs. On the
 * emulator the software decoder is what the app picks anyway, so nothing changes there.
 */
class SoftwareH264Decoder(private val inEveryTest: Boolean = true) : ExternalResource() {
    override fun before() {
        if (inEveryTest) choose()
    }

    /** From now on, until the test ends, the app's decoder is the software one (a decoder already running keeps its codec). */
    fun choose() {
        CodecNames.testOnlyForget()
        CodecNames.pick(VideoDecoder.AVC_DECODER_KEY) { softwareDecoderName() }
    }

    override fun after() = CodecNames.testOnlyForget()

    companion object {
        /** The name of the first software-only H.264 decoder; the test fails where there is none (every Android 10+ device has one). */
        fun softwareDecoderName(): String =
            checkNotNull(
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .firstOrNull { !it.isEncoder && it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }?.name,
            ) { "no software H.264 decoder on this device" }
    }
}
