package com.reau.app.aa

import com.google.protobuf.CodedOutputStream
import com.reau.app.aa.proto.MediaPlayback
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music and phone status channels, as Android Auto sends them, turned into the dash's cards. */
class AapCardsTest {
    private val songs = mutableListOf<AaNowPlaying?>()
    private val callers = mutableListOf<AaCaller?>()
    private val sources = mutableListOf<String>()
    private val cards = AapCards({ songs += it }, { callers += it }, { sources += it })

    private fun message(channel: Int, type: Int, payload: ByteArray, flags: Int = 0x0B): AapMessage {
        val bytes = byteArrayOf((type shr 8).toByte(), type.toByte()) + payload
        return AapMessage(channel, flags.toByte(), type, 2, bytes.size, bytes)
    }

    private fun metadata(song: String, artist: String = "", album: String = ""): ByteArray =
        MediaPlayback.MediaMetaData.newBuilder().setSong(song).setArtist(artist).setAlbum(album).buildPartial().toByteArray()

    /** A PhoneStatus with one call per (state, number, contact name), written by hand like the parser reads it. */
    private fun phoneStatus(vararg calls: Triple<Int, String, String>, signal: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(out)
        for ((state, number, id) in calls) {
            val call = ByteArrayOutputStream()
            CodedOutputStream.newInstance(call).apply {
                writeEnum(1, state)
                writeUInt32(2, 0)
                if (number.isNotEmpty()) writeString(3, number)
                if (id.isNotEmpty()) writeString(4, id)
                writeBytes(6, com.google.protobuf.ByteString.copyFrom(ByteArray(10) { 1 }))
                flush()
            }
            coded.writeByteArray(1, call.toByteArray())
        }
        coded.writeUInt32(2, signal)
        coded.flush()
        return out.toByteArray()
    }

    @Test
    fun `the song Android Auto plays becomes the music card`() {
        assertTrue(cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, metadata("Tempo Perdido", "Legião Urbana", "Dois"))))
        assertEquals(AaNowPlaying("Tempo Perdido", "Legião Urbana", "Dois"), songs.single())
    }

    @Test
    fun `a song with no title is no card`() {
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, metadata("")))
        assertNull(songs.single())
    }

    @Test
    fun `a song sent in pieces (with its album art) is read once whole`() {
        val bytes = MediaPlayback.MediaMetaData.newBuilder().setSong("Song").setArtist("Band").setAlbum("Album")
            .setAlbumArt(com.google.protobuf.ByteString.copyFrom(ByteArray(3_000) { 7 })).buildPartial().toByteArray()
        assertTrue(cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, bytes.copyOfRange(0, 1_000), flags = 0x09)))
        val middle = bytes.copyOfRange(1_000, 2_000)
        assertTrue(cards.take(AapMessage(Channel.ID_MPB, 0x08, 0x0707, 2, middle.size, middle)))
        val last = bytes.copyOfRange(2_000, bytes.size)
        assertTrue(cards.take(AapMessage(Channel.ID_MPB, 0x0A, 0x0707, 2, last.size, last)))
        assertEquals(1, songs.size)
        assertEquals(listOf("Song", "Band", "Album"), songs.single()!!.let { listOf(it.song, it.artist, it.album) })
        assertTrue("the picture came whole, in the 3,000 bytes sent", songs.single()!!.art.contentEquals(ByteArray(3_000) { 7 }))
    }

    @Test
    fun `a song without a picture has none`() {
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, metadata("Song", "Band", "Album")))
        assertEquals(null, songs.single()!!.art)
    }

    private fun status(state: MediaPlayback.MediaPlaybackStatus.State): ByteArray =
        MediaPlayback.MediaPlaybackStatus.newBuilder().setState(state).buildPartial().toByteArray()

    /** The dash's music card says playing or paused, as Android Auto's playback status says. */
    @Test
    fun `the song is playing until Android Auto says paused, and the card follows`() {
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, metadata("Song", "Band", "Album")))
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_STATUS, status(MediaPlayback.MediaPlaybackStatus.State.PAUSED)))
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_STATUS, status(MediaPlayback.MediaPlaybackStatus.State.PAUSED)))
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_STATUS, status(MediaPlayback.MediaPlaybackStatus.State.PLAYING)))
        assertEquals(
            "a repeated status (Android Auto sends one a second) is no new card",
            listOf(AaNowPlaying("Song", "Band", "Album", playing = true), AaNowPlaying("Song", "Band", "Album", playing = false),
                AaNowPlaying("Song", "Band", "Album", playing = true)),
            songs,
        )
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_STATUS, status(MediaPlayback.MediaPlaybackStatus.State.PAUSED)))
        cards.take(message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, metadata("Next", "Band", "Album")))
        assertEquals("a new song keeps the state", AaNowPlaying("Next", "Band", "Album", playing = false), songs.last())
    }

    /** The app the playback status names (such as "Spotify"), said once per change, for the title. */
    @Test
    fun `the music app the playback status names is said once per change`() {
        fun named(source: String, state: MediaPlayback.MediaPlaybackStatus.State? = null) =
            message(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_STATUS, MediaPlayback.MediaPlaybackStatus.newBuilder().setMediaSource(source)
                .apply { if (state != null) setState(state) }.buildPartial().toByteArray())
        cards.take(named("Spotify", MediaPlayback.MediaPlaybackStatus.State.PLAYING))
        cards.take(named("Spotify", MediaPlayback.MediaPlaybackStatus.State.PLAYING))
        cards.take(named("Spotify"))
        cards.take(named(""))
        cards.take(named("YouTube Music"))
        assertEquals("with no state too; a blank one is none", listOf("Spotify", "YouTube Music"), sources)
        assertTrue("no card", songs.isEmpty())
    }

    @Test
    fun `the playback status alone changes no card`() {
        assertTrue(cards.take(message(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.newBuilder().buildPartial().toByteArray())))
        assertTrue(songs.isEmpty())
    }

    @Test
    fun `a ringing call shows the contact's name, else the number, else an empty name, which the card shows as the private word`() {
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(4, "+5511999990000", "Mãe"))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus()))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(4, "+5511999990000", ""))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus()))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(4, "", ""))))
        assertEquals(listOf(AaCaller("Mãe"), null, AaCaller("+5511999990000"), null, AaCaller("")), callers)
    }

    @Test
    fun `a call answered goes on as the card in a call, and over is no card, the same state said once`() {
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(4, "1", "Ana"))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(4, "1", "Ana"))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(1, "1", "Ana"))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(2, "1", "Ana"))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(3, "1", "Ana"))))
        assertEquals(listOf(AaCaller("Ana"), AaCaller("Ana", inCall = true), null), callers)
    }

    @Test
    fun `a second call ringing during the first one shows the ringing one`() {
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(1, "1", "Ana"), Triple(4, "2", "Beto"))))
        assertEquals(listOf(AaCaller("Beto")), callers)
    }

    @Test
    fun `opening the channels stays with the control layer, and other channels are not taken`() {
        val open = com.reau.app.aa.proto.Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(Channel.ID_MPB).build()
        assertFalse(cards.take(message(Channel.ID_MPB, 7, open.toByteArray())))
        assertFalse(cards.take(message(Channel.ID_PHONE, 7, open.toByteArray())))
        assertFalse(cards.take(message(Channel.ID_NAV, AapCards.MEDIA_PLAYBACK_METADATA, metadata("x"))))
        assertTrue(songs.isEmpty() && callers.isEmpty())
    }

    @Test
    fun `a broken message is dropped without stopping the next`() {
        assertTrue(cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, byteArrayOf(0x0A, 0x7F, 0x01))))
        cards.take(message(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(Triple(4, "1", "Ana"))))
        assertEquals(listOf(AaCaller("Ana")), callers)
    }
}
