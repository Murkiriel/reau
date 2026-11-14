package com.reau.app.aa

import android.view.Surface

/**
 * The video side of one Android Auto session: the messages of the video channel go in through [process] and the
 * decoded pictures come out on the surface given to [setSurface].
 *
 * [requestKeyframe] is called when the picture cannot go on from what has arrived: a frame came in broken
 * ([AapVideo]), the decoder stopped giving pictures and was restarted, or took in a picture too large ([VideoDecoder]). A picture
 * lost anywhere else on its way to the decoder or inside it (a full queue, an exception, a codec not yet started) asks the same
 * through [AapVideo.frameLost]. The session answers it
 * by cycling video focus, which makes Android Auto send a keyframe.
 *
 * [process] runs on the session's read thread and returns whether the message was video: it is the `video`
 * argument of [AapMessageHandlerType]. It only copies each frame into [AaVideoPipe]; the decoder is fed
 * on the pipe's own thread, so the read (and the frame's ack) no longer waits for the decoder's input.
 */
internal class AaVideoSink(
    stats: AaStats? = null,
    /** How long the compositor the decoder draws into has been stuck inside one frame ([VideoDecoder.downstreamBusyMs]). */
    compositorBusyMs: () -> Long = { 0L },
    private val requestKeyframe: () -> Unit,
) {
    private val decoder = VideoDecoder().apply {
        downstreamBusyMs = compositorBusyMs
        // Used only if a picture arrives before any codec configuration.
        fallbackWidth = AaVideo.WIDTH
        fallbackHeight = AaVideo.HEIGHT
        onDecoderError = requestKeyframe
        onFrameLost = { why -> video.frameLost(why) }
        onDecoded = stats?.let { s -> { latencyUs -> s.decoded(latencyUs) } }
    }
    private val pipe: AaVideoPipe =
        AaVideoPipe(
            decode = decoder::decode,
            onDropped = { video.decoderBehind() },
            onFailed = { video.frameLost("the decoder failed on a frame") },
            decoderReady = { decoder.isRunning },
        ).apply { start() }
    private val video: AapVideo = AapVideo(decode = pipe::put, onFrameCorrupted = requestKeyframe, stats = stats)

    /** Where the decoder draws. Null, or a new surface, stops it; it starts again with the next message. */
    fun setSurface(surface: Surface?) = decoder.setSurface(surface)

    fun process(message: AapMessage): Boolean = video.process(message)

    /** For the end of the session. */
    fun release() {
        video.release()
        pipe.stop()
        decoder.stop("session over")
    }
}
