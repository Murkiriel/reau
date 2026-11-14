package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 300 ms wait of `getMetrics()` (doubt of plan 23, item 32 of the 11/11/2026 list): the end of a codec waits that long and goes on to release it.
 * A read that answers after the wait would stand in the log under "no answer ... given up" as if it were the codec's own state, or, on a codec already
 * released, as "not available"; now it carries a note that says it came late. What is written when the answer is on time is as before.
 */
class CodecMetricsLateTest {
    private val prefix = "android.media.mediacodec."
    private val metrics = mapOf<String, Any?>(prefix + "codec" to "c2.test.avc.encoder", prefix + "mime" to "video/avc")

    private fun run(joinMs: Long, takes: Long, answer: Map<String, Any?>?): List<String> {
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        CodecMetrics.logBounded("encoder", "c2.test.avc.encoder", { Thread.sleep(takes); answer }, joinMs) { lines += it }
        Thread.sleep(takes + 200) // the thread left behind finishes
        return lines.toList()
    }

    @Test
    fun `an answer on time is the line as before, with no note`() {
        val lines = run(joinMs = 1_000, takes = 5, answer = metrics)
        assertEquals(listOf("codec metrics (encoder c2.test.avc.encoder): codec=c2.test.avc.encoder mime=video/avc"), lines)
    }

    @Test
    fun `no answer in time is told at once, and the answer that comes after says it came late`() {
        val lines = run(joinMs = 50, takes = 400, answer = metrics)
        assertEquals(lines.toString(), 2, lines.size)
        assertEquals("codec metrics (encoder c2.test.avc.encoder): no answer in 50 ms, given up", lines[0])
        assertTrue(lines[1], lines[1].startsWith("codec metrics (encoder c2.test.avc.encoder): codec=c2.test.avc.encoder"))
        assertTrue(lines[1], lines[1].endsWith(CodecMetrics.lateNote(50)))
    }

    @Test
    fun `a late read that finds no metrics (the codec was released) is told as late, not as a plain not available`() {
        val lines = run(joinMs = 50, takes = 400, answer = null)
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[1], "not available" in lines[1] && lines[1].endsWith(CodecMetrics.lateNote(50)))
    }

    @Test
    fun `a codec with no metrics on time is plainly not available`() {
        val lines = run(joinMs = 1_000, takes = 5, answer = null)
        assertEquals(listOf("codec metrics (encoder c2.test.avc.encoder): not available"), lines)
        assertFalse(lines.single().contains("late"))
    }
}
