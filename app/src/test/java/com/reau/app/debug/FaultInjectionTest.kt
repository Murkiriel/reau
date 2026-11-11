package com.reau.app.debug

import com.reau.app.data.RideDiagnostics
import com.reau.app.debug.FaultInjection.Fault
import com.reau.app.stream.EncoderMuteWatch
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The debug build's fault injection (item 20 of the 11/11/2026 list; sources in `src/debug`): what arming each fault changes for the three places that
 * ask, that each is served once, and what the ride log says. The release build has none of it (FaultInjectionAbsenceInReleaseTest).
 */
class FaultInjectionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: File

    @Before
    fun setUp() {
        FaultInjection.reset()
        log = tmp.newFile("ride-fault.log")
        RideDiagnostics.testOnlySetFile(log)
    }

    @After
    fun tearDown() {
        FaultInjection.reset()
        RideDiagnostics.testOnlyReset()
    }

    private fun told(): List<String> {
        RideDiagnostics.logAndWait("ui", "end")
        return log.readLines().filter { "[debug]" in it }
    }

    @Test
    fun `nothing is armed at the start, so every question is answered no`() {
        assertFalse(FaultInjection.swallowDecoderOutput())
        assertFalse(FaultInjection.muteEncoderOutput())
        assertFalse(FaultInjection.takeFocusCycle())
        assertEquals(emptySet<Fault>(), FaultInjection.armedNow())
    }

    @Test
    fun `the decoder stall hides the decoder's output until the watch restarts it, once`() {
        FaultInjection.arm(Fault.DECODER_STALL)
        assertTrue(FaultInjection.swallowDecoderOutput())
        assertTrue("still, until it is served", FaultInjection.swallowDecoderOutput())
        FaultInjection.decoderRestarted()
        assertFalse("served: the pictures count again", FaultInjection.swallowDecoderOutput())
        FaultInjection.decoderRestarted() // a restart that was not the injected one says nothing
        val lines = told()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], "fault injection armed: DECODER_STALL" in lines[0])
        assertTrue(lines[1], "served" in lines[1])
    }

    @Test
    fun `the encoder mute hides the frames out until the mute watch gives a verdict, and that watch then rebuilds`() {
        FaultInjection.arm(Fault.ENCODER_MUTE)
        val watch = EncoderMuteWatch()
        // As VideoLoop polls it: the frames out are zero while the fault is armed, whatever the encoder really did (here, 10 frames in and 10 out per poll).
        var verdict = EncoderMuteWatch.Verdict.FINE
        var now = 0L
        while (verdict == EncoderMuteWatch.Verdict.FINE && now < 10_000) {
            now += 500
            verdict = watch.onPoll(now, submitted = 10, framesOut = if (FaultInjection.muteEncoderOutput()) 0 else 10)
        }
        assertEquals(EncoderMuteWatch.Verdict.REBUILD, verdict)
        assertTrue("after about 3 s", now in 3_000..4_000)
        FaultInjection.encoderVerdictGiven()
        assertFalse(FaultInjection.muteEncoderOutput())
        assertEquals("with the fault served the watch sees the real frames: fine", EncoderMuteWatch.Verdict.FINE, watch.onPoll(now + 500, 10, 10))
    }

    @Test
    fun `the focus cycle is taken once for each press`() {
        FaultInjection.arm(Fault.FOCUS_CYCLE)
        FaultInjection.arm(Fault.FOCUS_CYCLE) // two presses before a tick are one request: the gate would not allow two in a second anyway
        assertTrue(FaultInjection.takeFocusCycle())
        assertFalse(FaultInjection.takeFocusCycle())
    }

    @Test
    fun `each fault is armed apart from the others`() {
        FaultInjection.arm(Fault.ENCODER_MUTE)
        assertFalse(FaultInjection.swallowDecoderOutput())
        assertFalse(FaultInjection.takeFocusCycle())
        assertTrue(FaultInjection.muteEncoderOutput())
        assertEquals(setOf(Fault.ENCODER_MUTE), FaultInjection.armedNow())
    }

    @Test
    fun `the line of a fired notice says which and that it is the debug build`() {
        assertEquals("debug trigger fired: dash stuck (debug build only)", DebugTriggers.firedLine("dash stuck"))
    }
}
