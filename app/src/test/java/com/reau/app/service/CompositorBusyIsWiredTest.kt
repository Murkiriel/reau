package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The compositor tells how long it has been inside a frame, and the decoder that draws into it waits for that before it is called stalled (plan 18).
 * The line that gives the compositor's figure to the receiver lives in the service, which a JVM test cannot start: like
 * [GpsStopsWithTheProjectionTest], this reads the source. The path from the receiver to the decoder has instrumented tests of its own
 * (AaReceiverTest, AaVideoSinkTest, VideoDecoderTest), and the figure itself, FrameBusyTest and DashCompositorTest.
 */
class CompositorBusyIsWiredTest {
    private val source = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()

    @Test
    fun theReceiverIsGivenTheCompositorsBusyFigure() {
        assertTrue(
            "LiveSides must pass `downstreamBusyMs = compositor::busyMs` to the receiver's options",
            Regex("""options\.copy\(\s*downstreamBusyMs\s*=\s*compositor::busyMs\s*\)""").containsMatchIn(source),
        )
    }
}
