package com.reau.app.phone

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The log's line for the microphone of the assistant's voice: the kind of headset, never the name the rider gave it. */
class MicrophoneLineTest {
    @Test
    fun `the phone's own microphone is said as it always was`() {
        assertEquals("microphone: listening on the phone's own microphone", CallAudioRoute.microphoneLine(null))
    }

    @Test
    fun `a headset is said by its kind`() {
        val line = CallAudioRoute.microphoneLine(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals("microphone: listening on a Bluetooth headset (${CallAudioRoute.deviceName(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)})", line)
    }

    @Test
    fun `the voice's code asks for the line by the kind, and the headset's name is nowhere in it`() {
        val voice = File("src/main/java/com/reau/app/phone/PhoneVoice.kt").readText()
        assertTrue(voice.contains("log(CallAudioRoute.microphoneLine(headset?.type))"))
        assertTrue("the name a rider gives a headset is personal", !voice.contains("productName"))
    }
}
