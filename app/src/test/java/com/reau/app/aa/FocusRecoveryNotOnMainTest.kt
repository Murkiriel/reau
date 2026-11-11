package com.reau.app.aa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-21 of the independent review of 26/10/2026: "the video focus recovery goes through the main
 * thread" -- the gain of the focus was posted with `Handler(Looper.getMainLooper()).postDelayed(…, 100)`, so a main thread held up (the 1.3 s freeze)
 * delayed the request for a keyframe. In the code of today that is already not so: the gain goes out on the transport's own send thread (commit 8330c1e),
 * and the request itself ([AapTransport.triggerFocusCycleRecovery]) is called from the decoder's, the reader's and the once-a-second tick's threads, none
 * of them the main one. The doubt is closed by reading, and these tests hold the reading: they are of the text (the transport needs a Context and a
 * socket), the same kind as the other tests that guard a thread rule.
 */
class FocusRecoveryNotOnMainTest {
    private val transport = File("src/main/java/com/reau/app/aa/AapTransport.kt").readText()

    @Test
    fun `the transport never posts to the main looper`() {
        assertFalse("AapTransport uses the main looper", "getMainLooper" in transport || "Looper.getMain" in transport || "Dispatchers.Main" in transport)
    }

    @Test
    fun `the focus gain is posted to the send handler, after the gap`() {
        val cycle = transport.substringAfter("private fun cycleVideoFocus()").substringBefore("/** A keyframe of Android Auto arrived")
        assertTrue(cycle, "sendHandler?.postDelayed(" in cycle && "VideoFocusEvent(gain = true, unsolicited = true)" in cycle && "gapMs" in cycle)
        assertEquals("one post in the cycle", 1, Regex("""postDelayed""").findAll(cycle).count())
    }

    @Test
    fun `no caller of the focus cycle request is a screen or a main thread entry point`() {
        val callers = File("src/main/java/com/reau/app").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "triggerFocusCycleRecovery" in it.readText() }.map { it.name }.sorted().toList()
        assertEquals(callers.toString(), listOf("AaReceiver.kt", "AapTransport.kt"), callers)
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        assertFalse("AaReceiver posts to the main thread", "runOnUiThread" in receiver || "getMainLooper" in receiver)
    }
}
