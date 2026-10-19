package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dash's own music card (05 0D) and call card (05 22), sent by a real
 * [DashSession] to [FakeDashPeer] over loopback UDP, as DashSessionFakeDashTest does. Like that test, it proves what
 * goes on the wire, not that the real dash shows it.
 */
@RunWith(AndroidJUnit4::class)
class DashSessionCardsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
    }

    private fun streaming(): Pair<DashSession, FakeDashPeer> {
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) })
            .also { session = it }
        s.connect("DashFake")
        runBlocking {
            val deadline = System.currentTimeMillis() + 8_000
            while (s.state.value != DashState.READY && s.state.value != DashState.ERROR && System.currentTimeMillis() < deadline) delay(50)
        }
        assertEquals(DashState.READY, s.state.value)
        s.startStreaming()
        fake.ackIdrFrame()
        // No video goes out here, so nothing would come back, and since the RX watchdog of 4 s the session
        // fell to ERROR mid-test, stopping the very rounds these tests count. The dash talks as a real one does.
        scope.launch { while (isActive) { fake.ackPFrame(); delay(500) } }
        return s to fake
    }

    private fun pause(ms: Long) = runBlocking { delay(ms) }

    @Test
    fun withNoSongAndNoCallNeitherCardIsSent() {
        val (_, fake) = streaming()
        pause(2_500)
        assertEquals(0, fake.countOf(0x05, 0x0D))
        assertEquals(0, fake.countOf(0x05, 0x22))
    }

    @Test
    fun theSongIsRepeatedEverySecondUntilCleared() {
        val (s, fake) = streaming()
        s.updateNowPlaying("Song", "Album", "Band")
        pause(2_600)
        val sent = fake.countOf(0x05, 0x0D)
        assertTrue("about 2 or 3 music cards in 2.6 s, got $sent", sent in 2..4)
        val card = fake.received.last { it.type == 0x05 && it.sub == 0x0D }.value
        // Album, title, artist, in that order; changed on purpose.
        assertArrayEquals("Album\u0000Song\u0000Band".toByteArray(Charsets.UTF_8), card)
        // ...with its flags: music on, no album art, playing.
        fun last(sub: Int) = fake.received.last { it.type == 0x05 && it.sub == sub }.value.toList()
        assertEquals(listOf(0x55.toByte()), last(0x17))
        assertEquals(listOf(0xAA.toByte()), last(0x58))
        assertEquals(listOf(0x55.toByte()), last(0x19))
        s.updateNowPlaying("Song", "Album", "Band", playing = false)
        pause(1_200)
        assertEquals("paused", listOf(0xAA.toByte()), last(0x19))

        s.clearNowPlaying()
        pause(1_200)
        val after = fake.countOf(0x05, 0x0D)
        pause(2_000)
        assertEquals("no more music cards once cleared", after, fake.countOf(0x05, 0x0D))
        assertEquals("the dash is told once there is no song", listOf(0xAA.toByte()), last(0x17))
        // The connect burst already says "no music" (05 17 AA, in the captured burst); after the song, once.
        val sinceSong = fake.received.dropWhile { !(it.type == 0x05 && it.sub == 0x0D) }
        assertEquals(1, sinceSong.count { it.type == 0x05 && it.sub == 0x17 && it.value.single() == 0xAA.toByte() })
        assertEquals("the session still streams, so the rounds still run", DashState.STREAMING, s.state.value)
    }

    @Test
    fun aRingingCallGoesOutAtOnceAndItsClearOnce() {
        val (s, fake) = streaming()
        s.showCaller("Ana")
        pause(400)
        // The state (05 21 = 0A, then 05 4D = 32) and the name encrypted.
        val sinceCall = { fake.received.dropWhile { !(it.type == 0x05 && it.sub == 0x21 && it.value.single() == 0x0A.toByte()) } }
        assertEquals("ringing at once", listOf(0x21 to 0x0A, 0x4D to 0x32),
            sinceCall().filter { it.type == 0x05 && it.sub in setOf(0x21, 0x4D) }.take(2).map { it.sub to (it.value.single().toInt() and 0xFF) })
        val first = sinceCall().first { it.type == 0x05 && it.sub == 0x22 }.value
        assertArrayEquals("the call card at once, encrypted under the session key", "Ana\u0000".toByteArray(Charsets.ISO_8859_1),
            DashSession.aesDecryptCbc(first, fake.sessionKey!!))

        pause(2_000)
        assertTrue("repeated while ringing", fake.countOf(0x05, 0x22) >= 3)

        fun states(tlvs: List<com.reau.app.dash.protocol.Tlv>) =
            tlvs.filter { it.type == 0x05 && it.sub == 0x21 }.map { it.value.single().toInt() and 0xFF }
        // The 1 Hz status packets carry the call too: never "no call" while it rings.
        val ringing = states(sinceCall())
        assertTrue("several, all ringing: $ringing", ringing.size >= 3 && ringing.all { it == 0x0A })

        s.clearCaller()
        pause(400)
        assertEquals("cleared at once: no call", listOf(0x32.toByte()), fake.received.last { it.type == 0x05 && it.sub == 0x21 }.value.toList())
        val atClear = fake.received.size
        val afterClear = fake.countOf(0x05, 0x22)
        pause(2_200)
        assertEquals("no name after the clear", afterClear, fake.countOf(0x05, 0x22))
        val after = states(fake.received.drop(atClear))
        assertTrue("no call, every second: $after", after.isNotEmpty() && after.all { it == 0x32 })
        assertEquals("the session still streams, so the rounds still run", DashState.STREAMING, s.state.value)
    }
}
