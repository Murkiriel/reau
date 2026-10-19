package com.reau.app.aa

import android.graphics.ImageFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The link to Android Auto 17.3 and older, where the receiver listens and Android Auto dials in (the Lite edition's way,
 * AaCallsIn asks it to). The Android Auto here is the fake one in its dialling mode: each ask ([AaReceiver.CallIn.poke])
 * makes a new fake call the receiver's port.
 */
@RunWith(AndroidJUnit4::class)
class AaReceiverCallInTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<AaLinkState>()
    private val reader = ImageReader.newInstance(16, 16, ImageFormat.YUV_420_888, 2)
    private val peers = CopyOnWriteArrayList<FakeAndroidAutoPeer>()
    private val asks = AtomicInteger()
    private var receiver: AaReceiver? = null

    @Before
    fun setUp() {
        AaLog.sink = { log += it }
    }

    @After
    fun tearDown() {
        receiver?.stop()
        peers.forEach { it.close() }
        reader.close()
        AaLog.sink = null
    }

    private val quick = AaReceiver.Timing(redialMs = 200, afterDropMs = 100)

    /** A port nothing listens on. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A receiver that listens on [port] and, at each ask, has a new fake Android Auto call it ([answerAsks]) or nobody. */
    private fun receiverCalledOn(port: Int, answerAsks: Boolean = true): AaReceiver {
        val poke = {
            asks.incrementAndGet()
            if (answerAsks) FakeAndroidAutoPeer(dials = true).also { peers += it }.dial(port)
        }
        val options = AaReceiver.Options(timing = quick, callIn = AaReceiver.CallIn(port = port, waitMs = 300, poke = poke))
        return AaReceiver(context, AaConfig(density = 180), reader.surface, { states += it }, options).also { receiver = it }
    }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$what (states: $states, asks: ${asks.get()}, log: $log)", condition())
    }

    @Test
    fun androidAutoCallingInGetsASession() {
        receiverCalledOn(freePort()).start()
        await("a session is up") { states.lastOrNull() == AaLinkState.ACTIVE }
        assertTrue("the fake finished the handshake", peers.first().awaitHandshake())
        assertTrue("it was asked at least once: ${asks.get()}", asks.get() >= 1)
        assertEquals("never SERVER_OFF: that is the other way's state: $states", false, AaLinkState.SERVER_OFF in states)
    }

    @Test
    fun androidAutoIsNotAskedAgainWhileASessionRuns() {
        receiverCalledOn(freePort()).start()
        await("a session is up") { states.lastOrNull() == AaLinkState.ACTIVE }
        val before = asks.get()
        Thread.sleep(1_500)
        assertEquals("asked while the session ran (log: $log)", before, asks.get())
        assertEquals("still the same session: ${peers.size} peers", AaLinkState.ACTIVE, states.last())
    }

    @Test
    fun aSessionThatEndsIsFollowedByAnotherCall() {
        receiverCalledOn(freePort()).start()
        await("the first session is up") { states.lastOrNull() == AaLinkState.ACTIVE }
        peers.first().hangUp()
        await("the drop is seen") { AaLinkState.DROPPED in states }
        await("a second Android Auto called in") { peers.size >= 2 && states.lastOrNull() == AaLinkState.ACTIVE }
        assertTrue("the second one finished the handshake", peers[1].awaitHandshake())
    }

    @Test
    fun androidAutoNotCallingAfterSeveralAsksIsSaidAndTheAsksGoOn() {
        receiverCalledOn(freePort(), answerAsks = false).start()
        await("it says Android Auto is not calling") { AaLinkState.NOT_CALLING in states }
        assertTrue("said only after ${AaReceiver.CALL_IN_TRIES_BEFORE_SAYING} asks: ${asks.get()}", asks.get() >= AaReceiver.CALL_IN_TRIES_BEFORE_SAYING)
        assertEquals("the state is said once: $states", 1, states.count { it == AaLinkState.NOT_CALLING })
        val before = asks.get()
        await("it goes on asking after saying so") { asks.get() > before }
    }

    @Test
    fun aCallAfterTheWarningBringsTheSessionUp() {
        val answer = java.util.concurrent.atomic.AtomicBoolean(false)
        val port = freePort()
        val poke = {
            asks.incrementAndGet()
            if (answer.get()) FakeAndroidAutoPeer(dials = true).also { peers += it }.dial(port)
        }
        val options = AaReceiver.Options(timing = quick, callIn = AaReceiver.CallIn(port = port, waitMs = 300, poke = poke))
        receiver = AaReceiver(context, AaConfig(density = 180), reader.surface, { states += it }, options).also { it.start() }
        await("it says Android Auto is not calling") { AaLinkState.NOT_CALLING in states }
        answer.set(true)
        await("the session is up") { states.lastOrNull() == AaLinkState.ACTIVE }
    }

    @Test
    fun withNoCallItKeepsAskingAndStopFreesThePort() {
        val port = freePort()
        val running = receiverCalledOn(port, answerAsks = false)
        running.start()
        await("asked again and again") { asks.get() >= 3 }
        assertTrue("no session without a call: $states", states.none { it == AaLinkState.ACTIVE })
        val begun = System.currentTimeMillis()
        running.stop()
        assertTrue("stop took ${System.currentTimeMillis() - begun} ms", System.currentTimeMillis() - begun < 3_000)
        // The port is free again: nothing left listening on it.
        ServerSocket(port, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).close()
    }
}
