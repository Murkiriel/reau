package com.reau.app.service

import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.AapCards
import com.reau.app.aa.Channel
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.FakeAndroidAutoVideo
import com.reau.app.aa.proto.MediaPlayback
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.video.TestH264Source
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dash's own music and call cards, inside the service: the fake Android Auto sends a song on the music
 * status channel (in pieces, as a song with album art comes) and a ringing call on the phone status channel, and the
 * fake dash must get its own music card (05 0D) and call card (05 22); the call answered turns it into an "in a call"
 * card, and the call over, or Android Auto gone, clears it. With the switches off, the dash gets neither card.
 */
@RunWith(AndroidJUnit4::class)
class CardsEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val aaPort = ServerSocket(0).use { it.localPort }
    private val androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
    private val video = FakeAndroidAutoVideo(androidAuto, TestH264Source.encode(frames = 15, keyframeEvery = 15))
    private val dash = FakeDashPeer().apply { autoAck = true }

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) = onReady("DashFake", null)
        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the service gone", 10_000) { !serviceRunning() }
        video.close()
        androidAuto.close()
        dash.close()
        ProjectionService.sidesFactory = originalSides
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun serviceRunning(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == ProjectionService::class.java.name }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$what (projection: ${ProjectionService.projection.value}, cards at the dash: ${cards()})", condition())
    }

    private fun startStreaming(media: Boolean, phone: Boolean) {
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = AaConfig(media = media, phone = phone),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                ),
            )
        }
        ProjectionService.start(context)
        await("streaming", 40_000) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
    }

    /**
     * The music (05 0D) and call cards the dash got, their values as text (NUL shown as '|'): the caller's name (05 22)
     * decrypted under the session key, and the call's state (05 21) in hex.
     */
    private fun cards(): List<String> = dash.receivedSinceHandshake()
        .filter { it.type == 0x05 && (it.sub == 0x0D || it.sub == 0x22) }
        .map {
            when (it.sub) {
                0x22 -> "22:" + String(com.reau.app.dash.DashSession.aesDecryptCbc(it.value, dash.sessionKey!!) ?: byteArrayOf(),
                    Charsets.ISO_8859_1).replace('\u0000', '|')
                else -> "0D:" + String(it.value, Charsets.UTF_8).replace('\u0000', '|')
            }
        }

    /** The call states (05 21) the dash got, in hex; the 1 Hz status packets carry one too. */
    private fun callStates(): List<String> = dash.receivedSinceHandshake()
        .filter { it.type == 0x05 && it.sub == 0x21 }.map { "%02X".format(it.value.single()) }

    private fun song(): ByteArray = MediaPlayback.MediaMetaData.newBuilder().setSong("Song").setArtist("Band").setAlbum("Album")
        .setAlbumArt(ByteString.copyFrom(ByteArray(4_000) { 7 })).buildPartial().toByteArray()

    private fun phoneStatus(state: Int?, name: String = "Ana"): ByteArray {
        val out = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(out)
        if (state != null) {
            val call = ByteArrayOutputStream()
            CodedOutputStream.newInstance(call).apply {
                writeEnum(1, state); writeUInt32(2, 0); writeString(3, "+5511999990000"); writeString(4, name); flush()
            }
            coded.writeByteArray(1, call.toByteArray())
        }
        coded.writeUInt32(2, 4)
        coded.flush()
        return out.toByteArray()
    }

    @Test
    fun theSongAndTheRingingCallReachTheDashsOwnCards() {
        startStreaming(media = true, phone = true)

        androidAuto.sendInPieces(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, song(), pieces = 3)
        // Album, title, artist, in that order; changed on purpose.
        await("the music card", 10_000) { "0D:Album|Song|Band" in cards() }

        androidAuto.send(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(AapCards.STATE_INCOMING))
        await("the call card", 5_000) { "22:Ana|" in cards() }

        // Answered (IN_CALL): the card stays and says "in a call" (state 0x14) with the same name.
        androidAuto.send(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(state = 1))
        await("the call card says in a call", 5_000) { callStates().lastOrNull() == "14" }
        assertTrue("still the caller's name: ${cards()}", cards().last { it.startsWith("22:") } == "22:Ana|")

        // The call over (a status with no call in it): the card is cleared.
        androidAuto.send(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(state = null))
        await("the call card cleared", 5_000) { callStates().lastOrNull() == "32" }
    }

    @Test
    fun androidAutoGoingAwayWhileRingingClearsTheCallCard() {
        startStreaming(media = false, phone = true)
        androidAuto.send(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(AapCards.STATE_INCOMING))
        await("the call card", 10_000) { "22:Ana|" in cards() }

        video.close()
        androidAuto.close()
        await("Android Auto seen as gone", 10_000) { ProjectionService.projection.value.androidAuto != AaLinkState.ACTIVE }
        await("the call card cleared", 5_000) { callStates().lastOrNull() == "32" }
    }

    @Test
    fun withTheSwitchesOffTheDashGetsNoCard() {
        startStreaming(media = false, phone = false)
        androidAuto.send(Channel.ID_MPB, AapCards.MEDIA_PLAYBACK_METADATA, song())
        androidAuto.send(Channel.ID_PHONE, AapCards.PHONE_STATUS, phoneStatus(AapCards.STATE_INCOMING))
        Thread.sleep(3_000)
        assertTrue("no card: ${cards()}", cards().isEmpty())
    }
}
