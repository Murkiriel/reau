package com.reau.app.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the emulator: REau's own logcat goes to a file while the
 * app lives (started by REauApp; the instrumentation runs in the app's process), the reader comes back after it dies,
 * and nothing of another process gets in.
 */
@RunWith(AndroidJUnit4::class)
class OwnLogcatTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val myPid = android.os.Process.myPid().toString()

    @Before
    fun setUp() = OwnLogcat.start(context) // a no-op when REauApp already started it

    private fun awaitInFile(marker: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (OwnLogcat.currentFile()?.readText()?.contains(marker) == true) return
            Thread.sleep(200)
        }
        assertTrue("$marker not in ${OwnLogcat.currentFile()}", false)
    }

    @Test
    fun aLineLoggedByTheAppReachesTheFile() {
        assertNotNull("a logcat file", OwnLogcat.currentFile())
        val marker = "own-logcat-marker-${System.nanoTime()}"
        Log.d("OwnLogcatTest", marker)
        awaitInFile(marker)
    }

    @Test
    fun theReaderComesBackAfterItDies() {
        val before = "before-kill-${System.nanoTime()}"
        Log.d("OwnLogcatTest", before)
        awaitInFile(before)
        OwnLogcat.testOnlyKillReader()
        Thread.sleep(3_000) // the pump's pause before opening again
        val after = "after-kill-${System.nanoTime()}"
        Log.d("OwnLogcatTest", after)
        awaitInFile(after)
    }

    @Test
    fun nothingOfAnotherProcessGetsIn() {
        // A child process has the app's own uid (so the app may read its lines) but another pid: only --pid keeps it out.
        val other = "other-process-${System.nanoTime()}"
        ProcessBuilder("log", "-t", "OwnLogcatTest", other).start().waitFor()
        val marker = "own-pid-check-${System.nanoTime()}"
        Log.d("OwnLogcatTest", marker)
        awaitInFile(marker)
        Thread.sleep(1_000)
        assertTrue("the child's line kept out", OwnLogcat.currentFile()!!.readText().contains(other).not())
        // threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L tag: message"; the "--------- beginning of" lines have no pid.
        val pids = OwnLogcat.currentFile()!!.readLines().filter { !it.startsWith("-") && it.isNotBlank() }
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(2) }.toSet()
        assertTrue("only this process ($myPid): $pids", pids == setOf(myPid))
    }
}
