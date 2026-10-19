package com.reau.app.aa

/**
 * The sound Android Auto sends to REau when REau announced the media and guidance sinks (for the web server and the
 * phone's own playback): the PCM of a whole data message on the media channel (6) or the guidance channel (4). The
 * system sounds (channel 5) stay thrown away. A message cut in pieces is not taken (those are for video; sound comes
 * in small whole messages).
 */
object AaAudioFeed {
    /** One of the three streams REau takes, with the format announced for it. */
    enum class Stream(val sampleRate: Int, val channels: Int) {
        MEDIA(48_000, 2),
        GUIDANCE(16_000, 1),
        SPEECH(16_000, 1);

        /** Bytes of one sample of every channel (16 bits each). */
        val frameBytes: Int get() = 2 * channels
    }

    /** A piece of sound: whole frames of 16 bit little endian samples of [stream]. */
    class Pcm(val stream: Stream, val data: ByteArray)

    private const val WHOLE = 11
    private const val PAYLOAD_AFTER_TIMESTAMP = 10

    /** The PCM [message] carries, as a copy, or null when it is not sound REau takes. */
    internal fun pcm(message: AapMessage): Pcm? {
        val stream = streamOf(message.channel)
        val whole = message.type == 0 && message.flags.toInt() == WHOLE
        val frames = if (stream == null) 0 else (message.size - PAYLOAD_AFTER_TIMESTAMP) / stream.frameBytes
        return if (stream == null || !whole || frames <= 0) {
            null
        } else {
            Pcm(stream, message.data.copyOfRange(PAYLOAD_AFTER_TIMESTAMP, PAYLOAD_AFTER_TIMESTAMP + frames * stream.frameBytes))
        }
    }

    private fun streamOf(channel: Int): Stream? = when (channel) {
        Channel.ID_AUD -> Stream.MEDIA
        Channel.ID_AU1 -> Stream.GUIDANCE
        Channel.ID_SPE -> Stream.SPEECH
        else -> null
    }
}
