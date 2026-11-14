package com.reau.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The service and the receiver need Android to run, so the lines that wire the rider's mark to the copy of the session recording
 * (MarkFreezerTest) are read from the source, as [DashRecoveryIsWiredTest] does.
 */
class MarkFreezeIsWiredTest {
    private val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
    private val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
    private val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()

    @Test
    fun `a mark asks the sides to keep the session recording, after the line of the mark and off the main thread`() {
        val mark = service.substringAfter("ACTION_MARK -> worker.execute {").substringBefore("ACTION_REFIT")
        assertTrue(mark.contains("sides?.freezeRecording(marks.count)"))
        assertTrue("after the mark's own line", mark.indexOf("marks.mark(") < mark.indexOf("freezeRecording"))
    }

    @Test
    fun `the live sides hand it to the receiver, which opens the files at once and copies on a thread of its own`() {
        assertTrue(live.contains("override fun freezeRecording(mark: Int): Boolean = receiver?.freezeRecording(mark) ?: false"))
        val freeze = receiver.substringAfter("fun freezeRecording(mark: Int): Boolean {").substringBefore("fun sendInput(")
        assertTrue(freeze.contains("sentRecorder?.snapshot()"))
        assertTrue(freeze.contains("freezeIo.execute"))
        assertTrue("the copy is inside the executor", freeze.indexOf("freezeIo.execute") < freeze.indexOf("keeper.freeze("))
        assertTrue(receiver.contains("Thread(it, \"mark-freeze\")"))
    }

    @Test
    fun `the session recorder in use is the ring one, so the copy has two halves to take`() {
        assertTrue(receiver.contains("ring = true"))
        val handler = File("src/main/java/com/reau/app/aa/SessionRecorder.kt").readText().substringAfter("internal class RecordingHandler")
        assertFalse("nothing is copied on the read thread", handler.contains("snapshot") || handler.contains("freeze"))
    }
}
