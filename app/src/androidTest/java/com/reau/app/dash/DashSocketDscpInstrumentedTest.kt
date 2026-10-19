package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket

/** On Android (Linux) the mark really goes on the socket; Windows, where the unit tests run, ignores it. */
@RunWith(AndroidJUnit4::class)
class DashSocketDscpInstrumentedTest {

    @Test
    fun theControlAndVideoMarksAreKeptBySocketsOnAndroid() {
        for (tos in listOf(DashSocket.TOS_CONTROL, DashSocket.TOS_VIDEO)) {
            DatagramSocket().use { socket ->
                assertTrue(DashSocket.mark(socket, tos))
                assertEquals(tos, socket.trafficClass)
            }
        }
    }
}
