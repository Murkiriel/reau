package com.reau.app.aa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ask to Android Auto 17.3 and older ([AaCallsIn.poke]): it must never throw, whatever this phone has (the emulator's
 * Android Auto is a stub with neither the settings screen nor the receiver), and it must say, once the screen had its
 * moment in front, that the ask went out, naming the port it asked for.
 */
@RunWith(AndroidJUnit4::class)
class AaCallsInTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }

    @Test
    fun theAskGoesOutAndNamesThePort() {
        val said = CopyOnWriteArrayList<String>()
        AaCallsIn.poke(context, port = 5288) { said += it }
        assertTrue("the ask was not reported: $said", waitFor(5_000) { said.any { "asked Android Auto to dial 127.0.0.1:5288" in it } })
    }

    @Test
    fun theDefaultPortIsTheOneTheReceiverListensOn() {
        assertEquals(5288, AaCallsIn.PORT)
        assertEquals(AaCallsIn.PORT, AaReceiver.CallIn { }.port)
    }
}
