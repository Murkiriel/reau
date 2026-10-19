package com.reau.app.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The projection's foreground service types. Gemini needs the microphone type, or
 * Android hands a background service silence; it is asked for only with the switch on and the permission given, and
 * the connected device type stays first in the ladder's fallback.
 */
class ForegroundTypesTest {
    @Test
    fun `without Gemini the service is a connected device only, as before`() {
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE), ForegroundTypes.ladder(microphone = false))
    }

    @Test
    fun `with Gemini the microphone comes along, and the connected device alone is the fallback`() {
        assertEquals(
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ),
            ForegroundTypes.ladder(microphone = true, sdk = 34),
        )
    }

    @Test
    fun `on Android 10, which has no microphone type, the connected device alone`() {
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE), ForegroundTypes.ladder(microphone = true, sdk = 29))
    }
}
