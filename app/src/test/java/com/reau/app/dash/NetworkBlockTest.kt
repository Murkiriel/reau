package com.reau.app.dash

import org.junit.Assert.assertEquals
import java.io.IOException
import java.net.SocketException
import org.junit.Test

/** An EPERM from the network says what it means in the ride log, the others stay as they were. */
class NetworkBlockTest {
    @Test
    fun `an EPERM is the network blocked by Android`() {
        assertEquals(
            "SocketException: Binding socket to network 275 failed: EPERM (Operation not permitted) — network blocked by Android",
            NetworkBlock.describe(SocketException("Binding socket to network 275 failed: EPERM (Operation not permitted)")),
        )
        assertEquals(
            "IOException: sendto failed: EPERM (Operation not permitted) — network blocked by Android",
            NetworkBlock.describe(IOException("sendto failed: EPERM (Operation not permitted)")),
        )
    }

    @Test
    fun `other errors are told as before`() {
        assertEquals(
            "IOException: sendto failed: ENETUNREACH (Network is unreachable)",
            NetworkBlock.describe(IOException("sendto failed: ENETUNREACH (Network is unreachable)")),
        )
        assertEquals("IOException: null", NetworkBlock.describe(IOException()))
    }
}
