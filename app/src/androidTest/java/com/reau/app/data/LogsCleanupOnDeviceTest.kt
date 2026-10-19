package com.reau.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Apagar registros" on the emulator, with the app's real folders: the logs and the
 * last shared zip go, the recordings only with the second button, the reported marks stay, and the ride log and the
 * logcat copy go on in new files.
 */
@RunWith(AndroidJUnit4::class)
class LogsCleanupOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val diag = File(context.filesDir, "diag")
    private val video = File(context.filesDir, "video").apply { mkdirs() }
    private val shared = File(context.cacheDir, "shared").apply { mkdirs() }

    private fun awaitInRideLog(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val name = RideDiagnostics.currentFileName()
            if (name != null && File(diag, name).readText().contains(text)) return
            Thread.sleep(100)
        }
        assertTrue("'$text' not in the ride log ${RideDiagnostics.currentFileName()}", false)
    }

    @Test
    fun theLogsGoTheMarksStayAndTheLogGoesOn() {
        RideDiagnostics.startProcess("test") // a no-op when the app already opened it
        val oldCrash = File(diag, "crash-20261001-101010.txt").apply { writeText("an old trace") }
        val mark = File(diag, "crash-reported").apply { if (!exists()) writeText("0") }
        val zip = File(shared, "diagnostics-logs.zip").apply { writeText("zip") }
        val recording = File(video, "video-test.h264").apply { writeBytes(ByteArray(16)) }

        val cleared = LogsCleanup.run(context, withRecordings = false)

        assertTrue("counted: $cleared", cleared.files >= 2)
        assertFalse("the old crash went", oldCrash.exists())
        assertFalse("the shared zip went", zip.exists())
        assertTrue("the mark stays", mark.exists())
        assertTrue("the recordings stay without the second button", recording.exists())
        awaitInRideLog("the rider deleted the logs")
        assertNotNull("the logcat copy goes on", OwnLogcat.currentFile())

        LogsCleanup.run(context, withRecordings = true)
        assertFalse("the second button takes the recordings", recording.exists())
        awaitInRideLog("recordings included")
    }
}
