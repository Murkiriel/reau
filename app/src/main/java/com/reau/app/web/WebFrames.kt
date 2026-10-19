package com.reau.app.web

import com.reau.app.aa.AaAudioFeed

/**
 * What the browser's binary messages say they are: one byte in front. 0 is video (H.264, Annex-B),
 * 1 is the media sound and 2 the guide's voice (16 bit little endian PCM: 48 kHz stereo and 16 kHz mono, as announced to Android Auto).
 */
object WebFrames {
    const val VIDEO: Byte = 0
    const val MEDIA: Byte = 1
    const val GUIDANCE: Byte = 2

    fun video(data: ByteArray): ByteArray = tagged(VIDEO, data)

    fun audio(pcm: AaAudioFeed.Pcm): ByteArray =
        // The assistant's speech has the guide's format (16 kHz mono), so the page plays both as the one voice.
        tagged(if (pcm.stream == AaAudioFeed.Stream.MEDIA) MEDIA else GUIDANCE, pcm.data)

    private fun tagged(tag: Byte, data: ByteArray): ByteArray = ByteArray(data.size + 1).also {
        it[0] = tag
        data.copyInto(it, 1)
    }
}
