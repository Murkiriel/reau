package com.reau.app.phone

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaMicrophone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's real recorder for Gemini, on the emulator's virtual microphone: it opens
 * at 16 kHz mono, hands out chunks as they are heard, and once closed reads nothing more. The Bluetooth headset is
 * not on the emulator; that part is seen on the phone ("a ver no celular").
 */
@RunWith(AndroidJUnit4::class)
class PhoneVoiceOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val log = CopyOnWriteArrayList<String>()

    @Before
    fun grant() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun itRecordsTwentyMillisecondChunksUntilClosed() {
        val voice = PhoneVoice(context) { log += it }
        assertTrue("permitted after the grant", voice.permitted())
        assertTrue("it opened: $log", voice.open())
        try {
            val buffer = ShortArray(AaMicrophone.CHUNK_SAMPLES)
            val start = System.nanoTime()
            var samples = 0
            repeat(25) {
                val n = voice.read(buffer)
                assertTrue("read $n: $log", n > 0)
                samples += n
            }
            val seconds = (System.nanoTime() - start) / 1e9
            // 25 chunks of 20 ms: half a second of voice, about as long in time (the recorder's own buffer may hand
            // out the first bit at once).
            assertEquals(25 * AaMicrophone.CHUNK_SAMPLES, samples)
            assertTrue("about real time, took $seconds s", seconds in 0.25..2.0)
            assertTrue("it says where it listens: $log", log.any { "listening on the phone's own microphone" in it })
        } finally {
            voice.close()
        }
        assertEquals("nothing once closed", -1, voice.read(ShortArray(10)))
        voice.close() // twice is fine
    }
}
