package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `codecs:` line and the `encoder accepted:` text of the ride log (diagnostics only: nothing here picks a codec). */
class CodecReportTest {
    private val hw = CodecReport.Candidate("c2.qti.avc.encoder", hardware = true, sizeSupported = true, baselineSupported = true)
    private val sw = CodecReport.Candidate("c2.android.avc.encoder", hardware = false, sizeSupported = true, baselineSupported = false)

    @Test
    fun `the candidates say hardware or software and whether the size and Baseline are taken`() {
        assertEquals(
            "c2.qti.avc.encoder(hw size=ok baseline=ok) c2.android.avc.encoder(sw size=ok baseline=no)",
            CodecReport.candidates(listOf(hw, sw)),
        )
    }

    @Test
    fun `a capability that could not be read is a question mark, and no candidate is none`() {
        assertEquals("OMX.x(sw size=? baseline=?)", CodecReport.candidates(listOf(CodecReport.Candidate("OMX.x", false, null, null))))
        assertEquals("none", CodecReport.candidates(emptyList()))
    }

    @Test
    fun `the codecs line names the role, the stream, the candidates and the one chosen`() {
        assertEquals(
            "codecs: encoder video/avc 526x300 baseline [c2.qti.avc.encoder(hw size=ok baseline=ok)] chosen=c2.qti.avc.encoder (hw)",
            CodecReport.line("encoder", "video/avc", "526x300 baseline", CodecReport.candidates(listOf(hw)), "c2.qti.avc.encoder", true),
        )
        assertEquals(
            "codecs: decoder video/avc 800x480 [none] chosen=-",
            CodecReport.line("decoder", "video/avc", "800x480", "none", null, null),
        )
        assertTrue(CodecReport.line("decoder", "video/avc", "800x480", "x", "c2.android.avc.decoder", false).endsWith("chosen=c2.android.avc.decoder (sw)"))
    }

    @Test
    fun `what the codec accepted is read in a fixed order, with names for the mode and the profile`() {
        val got = mapOf<String, Number>(
            "latency" to 1, "bitrate-mode" to 2, "profile" to 1, "level" to 512, "i-frame-interval" to 2, "bitrate" to 400_000,
            "frame-rate" to 4, "priority" to 0, "operating-rate" to 4,
        )
        val asked = got
        assertEquals(
            "latency=1 bitrate-mode=CBR profile=Baseline level=512 i-frame-interval=2 bitrate=400000 frame-rate=4 priority=0 operating-rate=4",
            CodecReport.accepted(asked) { got[it] },
        )
    }

    @Test
    fun `a key the codec gave with another value than the one asked ends the text as a difference`() {
        val asked = mapOf<String, Number>("bitrate-mode" to 2, "i-frame-interval" to 2, "bitrate" to 400_000)
        val got = mapOf<String, Number>("bitrate-mode" to 1, "i-frame-interval" to 2.0f, "bitrate" to 200_000, "profile" to 0x10000)
        assertEquals(
            "bitrate-mode=VBR profile=ConstrainedBaseline i-frame-interval=2 bitrate=200000; differs: bitrate-mode asked 2 got 1, bitrate asked 400000 got 200000",
            CodecReport.accepted(asked) { got[it] },
        )
    }

    @Test
    fun `a key that was asked and the codec did not echo is told as not echoed, never as a difference`() {
        val asked = mapOf<String, Number>("latency" to 1, "bitrate-mode" to 2, "i-frame-interval" to 2, "bitrate" to 400_000)
        val got = mapOf<String, Number>("bitrate-mode" to 2, "bitrate" to 400_000, "frame-rate" to 4)
        val text = CodecReport.accepted(asked) { got[it] }
        assertEquals("bitrate-mode=CBR bitrate=400000 frame-rate=4; not echoed: latency, i-frame-interval", text)
        assertFalse(text, text.contains("differs"))
    }

    @Test
    fun `a key neither asked nor given is not mentioned, and a codec that echoes nothing says so`() {
        assertEquals("nothing echoed", CodecReport.accepted(emptyMap()) { null })
        assertEquals("nothing echoed; not echoed: latency, bitrate", CodecReport.accepted(mapOf("latency" to 1, "bitrate" to 400_000)) { null })
    }

    @Test
    fun `a value that is the same number as an int and as a float is no difference`() {
        assertEquals("i-frame-interval=2", CodecReport.accepted(mapOf<String, Number>("i-frame-interval" to 2)) { if (it == "i-frame-interval") 2.0f else null })
    }

    @Test
    fun `the output format wins over the input one, and the input one fills what the output left out`() {
        val output = mapOf<String, Number>("bitrate" to 200_000, "profile" to 1)
        val input = mapOf<String, Number>("bitrate" to 400_000, "frame-rate" to 4, "priority" to 0)
        val got = CodecReport.overlay({ output[it] }, { input[it] })
        assertEquals(200_000, got("bitrate"))
        assertEquals(4, got("frame-rate"))
        assertEquals(1, got("profile"))
        assertEquals(null, got("latency"))
        assertEquals(
            "profile=Baseline bitrate=200000 frame-rate=4 priority=0; not echoed: latency; differs: bitrate asked 400000 got 200000",
            CodecReport.accepted(mapOf("latency" to 1, "bitrate" to 400_000), got),
        )
    }

    @Test
    fun `a float interval keeps its fraction`() {
        assertEquals("i-frame-interval=1.5", CodecReport.accepted(emptyMap()) { if (it == "i-frame-interval") 1.5f else null })
    }

    @Test
    fun `an unknown mode or profile shows its number`() {
        val text = CodecReport.accepted(emptyMap()) { if (it == "bitrate-mode" || it == "profile") 99 else null }
        assertTrue(text, text.contains("bitrate-mode=99") && text.contains("profile=99"))
    }
}
