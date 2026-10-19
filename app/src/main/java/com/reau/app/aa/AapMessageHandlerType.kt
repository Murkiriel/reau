// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapMessageHandlerType.kt, commit 0abbe2a.
// Changes: package; it is given the configuration for the control layer, and the video path as a function (true = the
// message was video and is dealt with) instead of holding OpenCfMoto's AapVideo; and where the
// navigation guidance goes (null: the navigation status channel is not read); and Gemini's microphone (null: a request
// to open is refused, as before); and the music and phone status channels ([AapCards]; null, not read).
// Adapted from headunit-revived (AGPLv3): aap/AapMessageHandlerType.kt (video + control only)
package com.reau.app.aa

// One parameter per channel reader, each optional and named at the call: a holder object would only hide them.
@Suppress("LongParameterList")
internal class AapMessageHandlerType(
    private val transport: AapTransport,
    config: AaConfig,
    private val video: (AapMessage) -> Boolean,
    navigation: ((NavGuidance) -> Unit)?,
    microphone: AaMicrophone? = null,
    /** The music and phone status channels, when announced; null, neither is read. */
    private val cards: AapCards? = null,
    /** The web server's sound: the media and guidance PCM, when REau announced those sinks; null, thrown away. */
    private val audio: ((AaAudioFeed.Pcm) -> Unit)? = null,
) : AapMessageHandler {

    /** Without the navigation status channel. */
    constructor(transport: AapTransport, config: AaConfig, video: (AapMessage) -> Boolean = { false }) :
        this(transport, config, video, null)

    private val aapControl: AapControl = AapControlGateway(transport, config, navigation, microphone)

    @Throws(AapMessageHandler.HandleException::class)
    @Suppress("TooGenericExceptionCaught") // wrapped in HandleException and rethrown
    override fun handle(message: AapMessage) {
        if (tookMediaData(message)) return
        // The music and phone status messages, pieces included (a piece's first bytes are no type).
        if (cards?.take(message) == true) return
        val msgType = message.type

        // 3. Control message fallback.
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AaLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AaLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, message.flags, message.channel)
        }
    }

    /** Media data (types 0 and 1) dealt with and acknowledged: true when [message] was that. */
    private fun tookMediaData(message: AapMessage): Boolean {
        val data = message.type == 0 || message.type == 1
        return when {
            // 1. Video stream first (highest priority for smooth display).
            message.channel == Channel.ID_VID && video(message) -> {
                if (data) transport.sendMediaAck(message.channel)
                true
            }
            // 2. Audio: we advertise an audio sink to keep AA happy, but nav audio plays via the phone's
            //    own output → paired BT helmet (the motorcycle audio path), NOT through us — AA keeps
            //    audio local in self-mode. ACK data buffers so AA's unacked window never stalls, discard PCM.
            message.isAudio && data -> {
                // The ack goes whatever the sound's callback does: a callback that fails must not leave Android Auto's window unacknowledged.
                try {
                    if (audio != null && message.type == 0) AaAudioFeed.pcm(message)?.let(audio)
                } finally {
                    transport.sendMediaAck(message.channel)
                }
                true
            }
            else -> false
        }
    }
}
