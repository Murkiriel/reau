package com.reau.app.aa

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import com.reau.app.aa.proto.MediaPlayback

/**
 * What Android Auto says is playing, for the dash's own music card. [art] is the
 * album's picture as Android Auto hands it over (any image format, often large), null when it does not.
 */
@Suppress("ArrayInDataClass") // never compared: the picture is only handed on
data class AaNowPlaying(val song: String, val artist: String, val album: String, val playing: Boolean = true, val art: ByteArray? = null)

/**
 * A call ringing, for the dash's own call card. [name] is what the card shows, empty when the call has neither a name
 * nor a number (the card then says the word for a private number, in the rider's language: the one who shows it
 * has the resources). [inCall]: the call was answered and goes on (the card says "in a call" with the same name).
 */
data class AaCaller(val name: String, val inCall: Boolean = false)

/**
 * The music and phone status channels, read only when announced
 * ([AaConfig.media], [AaConfig.phone]). Every message on those two channels comes here, but a whole control message
 * (a channel being opened), which stays with the control layer.
 *
 * Music: Android Auto's metadata message (open-headunit's aap/AapMediaPlayback.kt, AGPL-3.0, commit 93c065dd, reads
 * it the same way) gives song, artist and album; a blank song is no card. Phone: the PhoneStatus message (aa-proxy-rs
 * protos.proto: calls = 1 { phone_state = 1, caller_number = 3, caller_id = 4 }) is read by hand, there is no
 * generated class for it here; a call in state INCOMING is a card with the contact's name, else the number, else
 * an empty name (see [AaCaller]); no ringing call, no card.
 *
 * Neither the song nor the caller is ever written to the log.
 */
internal class AapCards(
    private val onNowPlaying: ((AaNowPlaying?) -> Unit)?,
    private val onCaller: ((AaCaller?) -> Unit)?,
    private val onMediaApp: ((String) -> Unit)? = null,
) {
    private val media = AapFragments()
    private val phone = AapFragments()
    private var lastCaller: AaCaller? = null
    private var lastSong: AaNowPlaying? = null
    private var playing = true
    private var lastSource: String? = null

    /** True when [message] was for the music or phone channel and was dealt with here. */
    fun take(message: AapMessage): Boolean {
        val pieces = piecesFor(message) ?: return false
        pieces.take(message)?.let { complete -> read(message.channel, complete) }
        return true
    }

    /** Where [message]'s pieces go; null for another channel, or for a whole control message (a channel opening). */
    private fun piecesFor(message: AapMessage): AapFragments? {
        val pieces = when (message.channel) {
            Channel.ID_MPB -> media
            Channel.ID_PHONE -> phone
            else -> null
        }
        val whole = message.flags.toInt() and (AapFragments.FIRST or AapFragments.LAST) ==
            AapFragments.FIRST or AapFragments.LAST
        return if (whole && message.type < FIRST_SERVICE_TYPE) null else pieces
    }

    private fun read(channel: Int, complete: AapFragments.Whole) {
        runCatching {
            if (channel == Channel.ID_MPB) music(complete) else call(complete)
        }.onFailure { AaLog.w("AapCards: %s message not read: %s", Channel.name(channel), it.javaClass.simpleName) }
    }

    private fun music(whole: AapFragments.Whole) {
        when (whole.type) {
            MEDIA_PLAYBACK_METADATA -> song(MediaPlayback.MediaMetaData.parseFrom(whole.payload))
            MEDIA_PLAYBACK_STATUS -> MediaPlayback.MediaPlaybackStatus.parseFrom(whole.payload).let { source(it); state(it) }
        }
    }

    private fun song(meta: MediaPlayback.MediaMetaData) {
        val song = if (meta.song.isNullOrBlank()) null else AaNowPlaying(
            meta.song, meta.artist.orEmpty(), meta.album.orEmpty(), playing,
            art = if (meta.hasAlbumArt() && !meta.albumArt.isEmpty) meta.albumArt.toByteArray() else null,
        )
        lastSong = song
        AaLog.i("AapCards: music card %s", if (song == null) "none" else "updated")
        onNowPlaying?.invoke(song)
    }

    /** Playing or paused, said to the dash with the card's flag; Android Auto repeats it often. */
    private fun state(status: MediaPlayback.MediaPlaybackStatus) {
        if (!status.hasState()) return
        val now = status.state == MediaPlayback.MediaPlaybackStatus.State.PLAYING
        if (now == playing) return
        playing = now
        val song = lastSong?.copy(playing = now) ?: return
        lastSong = song
        AaLog.i("AapCards: music card %s", if (now) "playing" else "paused")
        onNowPlaying?.invoke(song)
    }

    /** The music app the status names ("Spotify"), said once per change, for the dash's title. */
    private fun source(status: MediaPlayback.MediaPlaybackStatus) {
        val source = status.mediaSource?.trim().orEmpty()
        if (!status.hasMediaSource() || source.isEmpty() || source == lastSource) return
        lastSource = source
        onMediaApp?.invoke(source)
    }

    private fun call(whole: AapFragments.Whole) {
        if (whole.type != PHONE_STATUS) return
        val caller = showing(whole.payload)
        if (caller == lastCaller) return
        lastCaller = caller
        AaLog.i("AapCards: call card %s", if (caller == null) "cleared" else if (caller.inCall) "in a call" else "ringing")
        onCaller?.invoke(caller)
    }

    companion object {
        /** Below this, a whole message is the control layer's (channel open is 7). */
        private const val FIRST_SERVICE_TYPE = 0x8000

        /** MediaPlaybackStatusMessageId.MEDIA_PLAYBACK_METADATA (aa-proxy-rs protos.proto). */
        const val MEDIA_PLAYBACK_METADATA = 32771
        /** The playback status (state, position) that open-headunit's protos call MSG_PLAYBACK_METADATA. */
        const val MEDIA_PLAYBACK_STATUS = 32769

        /** PhoneStatusMessageId.PHONE_STATUS (aa-proxy-rs protos.proto). */
        const val PHONE_STATUS = 32769

        /** PhoneStatus.State.INCOMING. */
        const val STATE_INCOMING = 4

        /**
         * PhoneStatus.State values of a call that was answered (aa-proxy-rs protos.proto: IN_CALL 1, ON_HOLD 2, CONFERENCED 5,
         * MUTED 6; INACTIVE 3 and UNKNOWN 0 are no call). A call that goes on keeps its card.
         */
        val STATES_IN_CALL = setOf(1, 2, 5, 6)

        /** The first ringing call of a PhoneStatus payload, or null when none rings. */
        fun ringing(payload: ByteArray): AaCaller? = calls(payload).firstOrNull { !it.inCall }

        /**
         * The call the dash's card shows: the first ringing one, else the first that was answered and goes
         * on; null with no call at all.
         */
        fun showing(payload: ByteArray): AaCaller? = calls(payload).let { all -> all.firstOrNull { !it.inCall } ?: all.firstOrNull() }

        /** Every call of a PhoneStatus payload that is ringing or going on, in the order Android Auto lists them. */
        private fun calls(payload: ByteArray): List<AaCaller> {
            val found = mutableListOf<AaCaller>()
            val input = CodedInputStream.newInstance(payload)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (WireFormat.getTagFieldNumber(tag) == 1 && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    call(input.readByteArray())?.let { found += it }
                } else {
                    input.skipField(tag)
                }
            }
            return found
        }

        /** One Call message: a card when its state is INCOMING (ringing) or one of [STATES_IN_CALL] (answered). */
        private fun call(bytes: ByteArray): AaCaller? {
            val input = CodedInputStream.newInstance(bytes)
            var state = -1
            var number = ""
            var id = ""
            while (!input.isAtEnd) {
                val tag = input.readTag()
                when (WireFormat.getTagFieldNumber(tag)) {
                    1 -> state = input.readEnum()
                    3 -> number = input.readString()
                    4 -> id = input.readString()
                    else -> input.skipField(tag)
                }
            }
            val inCall = state in STATES_IN_CALL
            if (state != STATE_INCOMING && !inCall) return null
            return AaCaller(id.trim().ifEmpty { number.trim() }, inCall)
        }
    }
}
