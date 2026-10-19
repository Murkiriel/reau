package com.reau.app.dash.video

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DashEncoderLowLatencyKeysTest {

    @Test
    fun `realtime priority and operating rate at the stream fps on every supported sdk`() {
        for (sdk in listOf(29, 30, 34)) {
            val keys = DashEncoder.lowLatencyKeys(sdk, 12)
            assertEquals("sdk=$sdk", 0, keys[MediaFormat.KEY_PRIORITY])
            assertEquals("sdk=$sdk", 12, keys[MediaFormat.KEY_OPERATING_RATE])
        }
    }

    @Test
    fun `latency key only from Android 11`() {
        assertFalse(DashEncoder.lowLatencyKeys(29, 8).containsKey(MediaFormat.KEY_LATENCY))
        assertEquals(1, DashEncoder.lowLatencyKeys(30, 8)[MediaFormat.KEY_LATENCY])
        assertEquals(1, DashEncoder.lowLatencyKeys(34, 8)[MediaFormat.KEY_LATENCY])
    }

    @Test
    fun `operating rate follows the fps it is given`() {
        for (fps in listOf(2, 8, 22, 30)) {
            assertEquals(fps, DashEncoder.lowLatencyKeys(34, fps)[MediaFormat.KEY_OPERATING_RATE])
        }
    }

    @Test
    fun `nothing else is set`() {
        assertEquals(setOf(MediaFormat.KEY_PRIORITY, MediaFormat.KEY_OPERATING_RATE, MediaFormat.KEY_LATENCY), DashEncoder.lowLatencyKeys(34, 8).keys)
        assertEquals(setOf(MediaFormat.KEY_PRIORITY, MediaFormat.KEY_OPERATING_RATE), DashEncoder.lowLatencyKeys(29, 8).keys)
    }
}
