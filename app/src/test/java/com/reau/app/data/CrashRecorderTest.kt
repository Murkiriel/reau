package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What made the app crash, written before the process dies: one `crash-….txt` per crash, with the thread, the time
 * since the process started and the whole stack (causes included), written synchronously; and the "last time it
 * crashed" the next run reports once.
 */
class CrashRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private fun recorder(dir: File = File(tmp.root, "diag")) = CrashRecorder(dir) { clock }

    private fun boom(): Throwable = IllegalArgumentException("outer boom", IllegalStateException("inner cause"))

    @Test
    fun `a crash is written at once, with the thread, the uptime and the whole stack`() {
        val file = recorder().record(Thread.currentThread(), boom(), uptimeMs = 12_345)
        assertNotNull(file)
        assertTrue("named crash-…: ${file!!.name}", file.name.startsWith("crash-") && file.name.endsWith(".txt"))
        val text = file.readText()
        assertTrue(text, text.contains("thread: ${Thread.currentThread().name}"))
        assertTrue(text, text.contains("uptime: 12345 ms"))
        assertTrue(text, text.contains("java.lang.IllegalArgumentException: outer boom"))
        assertTrue("the cause too: $text", text.contains("Caused by: java.lang.IllegalStateException: inner cause"))
        assertTrue("a frame of where it happened: $text", text.contains("CrashRecorderTest.boom"))
    }

    @Test
    fun `the folder is made if it is not there`() {
        val dir = File(tmp.root, "not/yet/there")
        assertNotNull(recorder(dir).record(Thread.currentThread(), boom(), 1))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `two crashes in the same second are two files`() {
        val r = recorder()
        val a = r.record(Thread.currentThread(), boom(), 1)
        val b = r.record(Thread.currentThread(), boom(), 2)
        assertNotEquals(a, b)
        assertEquals(2, File(tmp.root, "diag").listFiles { f -> f.name.startsWith("crash-") }!!.size)
    }

    @Test
    fun `the newest crash not yet reported is the one the next run tells`() {
        val r = recorder()
        assertNull("nothing yet", r.unreported())
        r.record(Thread.currentThread(), IllegalStateException("first"), 1)
        clock += 60_000
        val second = r.record(Thread.currentThread(), IllegalStateException("second"), 1)
        assertNotNull("the crash was written", second)
        assertEquals(second, r.unreported())
        r.markReported(second!!)
        assertNull("told once", r.unreported())
        clock += 60_000
        val third = r.record(Thread.currentThread(), IllegalStateException("third"), 1)
        assertEquals("a newer crash is told again", third, recorder().unreported())
    }

    @Test
    fun `the summary is the exception and where it was thrown`() {
        val e = boom()
        assertEquals("java.lang.IllegalArgumentException: outer boom at ${e.stackTrace.first()}", CrashRecorder.summary(e))
    }

    @Test
    fun `a folder that cannot be written costs nothing but the file`() {
        val notADir = tmp.newFile("diag-is-a-file")
        assertNull(recorder(notADir).record(Thread.currentThread(), boom(), 1))
    }
}
