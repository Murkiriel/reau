package com.reau.app.aa

import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The experimental longer gap of the focus cycle that asks Android Auto for a keyframe, and the measure of what a cycle bought. Off (the
 * default) the gap is the 100 ms REau always used and the cycle's own line is word for word what it was.
 */
class FocusGapTest {
    @Test
    fun `the switch is off by default, and off the gap is the 100 ms of always`() {
        assertFalse(REauSettings().experimentFocusGap)
        assertEquals(100L, FocusGap.ms(on = false))
        assertEquals(100L, FocusGap.DEFAULT_MS)
        assertEquals("the cycle's line gets no new word", "", FocusGap.words(100L))
    }

    @Test
    fun `on, the gap is 400 ms and the cycle's line says it`() {
        assertEquals(400L, FocusGap.ms(on = true))
        assertEquals(" (experiment: the focus comes back after 400 ms, not 100)", FocusGap.words(400L))
    }

    @Test
    fun `the first keyframe after a cycle says how long it took, once`() {
        val timing = FocusCycleTiming()
        assertNull("no cycle, nothing to say", timing.keyframe(500))
        timing.cycled(1_000, gapMs = 400)
        assertEquals("focus cycle: the keyframe came 730 ms after the cycle (focus given back after 400 ms)", timing.keyframe(1_730))
        assertNull("the next keyframe is an ordinary one", timing.keyframe(2_730))
    }

    @Test
    fun `a cycle that brings no keyframe in 5 s is told once, and a late keyframe still says its time`() {
        val timing = FocusCycleTiming()
        timing.cycled(1_000, gapMs = 100)
        assertNull(timing.overdue(5_999))
        assertEquals("focus cycle: no keyframe 5000 ms after the cycle (focus given back after 100 ms)", timing.overdue(6_000))
        assertNull("once", timing.overdue(7_000))
        assertEquals("focus cycle: the keyframe came 8000 ms after the cycle (focus given back after 100 ms)", timing.keyframe(9_000))
        assertNull(timing.overdue(20_000))
    }

    @Test
    fun `a new cycle starts the measure over`() {
        val timing = FocusCycleTiming()
        timing.cycled(1_000, gapMs = 100)
        timing.overdue(6_000)
        timing.cycled(10_000, gapMs = 400)
        assertEquals("focus cycle: no keyframe 5000 ms after the cycle (focus given back after 400 ms)", timing.overdue(15_000))
    }

    @Test
    fun `nothing is overdue with no cycle`() {
        assertNull(FocusCycleTiming().overdue(1_000_000))
    }

    @Test
    fun `the transport asks the gap at each cycle and uses it for the focus given back, and the keyframes reach the measure`() {
        val transport = File("src/main/java/com/reau/app/aa/AapTransport.kt").readText()
        val cycle = transport.substringAfter("private fun cycleVideoFocus()").substringBefore("internal fun keyframeArrived()")
        assertTrue(cycle.contains("val gapMs = focusGapMs()"))
        assertTrue(cycle.contains("}, gapMs)"))
        assertTrue(cycle.contains("\"AapTransport: Requesting recovery keyframe via focus cycle.\${FocusGap.words(gapMs)}\""))
        assertTrue("with nothing given, the transport uses the usual gap", transport.contains("private val focusGapMs: () -> Long = { FocusGap.DEFAULT_MS },"))
        assertFalse("the fixed 100 ms is gone from the transport", transport.contains("FOCUS_GAIN_DELAY_MS"))
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        assertTrue(receiver.contains("focusGapMs = focusGapMs)"))
        assertTrue(receiver.contains("stats.onKeyframe = transport::keyframeArrived"))
        assertTrue("with no option given, the receiver uses the usual gap", receiver.contains("val focusGapMs: () -> Long = { FocusGap.DEFAULT_MS },"))
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("focusGapMs = { com.reau.app.aa.FocusGap.ms(SettingsStore.open(context).load().experimentFocusGap) },"))
    }

    @Test
    fun `the statistics tell of each keyframe, and only of keyframes`() {
        val stats = AaStats()
        var told = 0
        stats.onKeyframe = { told++ }
        stats.unit(100, keyframe = false)
        stats.unit(9_000, keyframe = true)
        stats.unit(100, keyframe = false)
        assertEquals(1, told)
    }
}
