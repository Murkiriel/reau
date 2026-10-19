package com.reau.app.data

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ProjectionSides
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Why the app's processes ended before: a debug-only process of the app
 * (`:exittest`) is ended for real (am kill, a signal, an ANR, am force-stop) and the next projection's ride log must
 * tell the exit Android kept, once, with the right reason.
 */
@RunWith(AndroidJUnit4::class)
class ExitHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val diag = File(context.filesDir, "diag")
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val originalSides = ProjectionService.sidesFactory

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
    }

    @Test
    fun aProcessKilledInTheBackgroundIsToldOnceByTheNextProjection() {
        val pid = startExitTestProcess()
        await("in the background") { exitTestProcess()?.let { it.importance >= RunningAppProcessInfo.IMPORTANCE_SERVICE } == true }
        shell("am kill ${context.packageName}")
        awaitRecorded(pid)

        val line = exitLine(projectionLog(), pid)
        assertNotNull("the exit of pid $pid told", line)
        assertTrue(line, line!!.contains("user requested (10)") && line.contains("process ${context.packageName}:exittest"))
        assertNull("told once", exitLine(projectionLog(), pid))
    }

    @Test
    fun aProcessKilledBySignalIsToldWithTheSignal() {
        val pid = startExitTestProcess()
        Process.killProcess(pid)
        awaitRecorded(pid)

        val line = exitLine(projectionLog(), pid)
        assertNotNull("the exit of pid $pid told", line)
        assertTrue(line, line!!.contains("signaled (2)") && line.contains(", signal 9"))
    }

    @Test
    fun anAnrIsToldWithTheTraceAndroidKept() {
        context.sendBroadcast(
            Intent().setComponent(ComponentName(context.packageName, "com.reau.app.debug.ExitTestReceiver"))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("holdMs", 60_000L),
        )
        await("the :exittest process up") { exitTestProcess() != null }
        val pid = exitTestProcess()!!.pid
        awaitRecorded(pid, timeoutMs = 60_000)

        val line = exitLine(projectionLog(), pid)
        assertNotNull("the exit of pid $pid told", line)
        assertTrue(line, line!!.contains("ANR (6)"))
        val name = Regex("trace in (anr-\\S+\\.txt)").find(line)?.groupValues?.get(1)
        assertNotNull("the trace named on the line: $line", name)
        val trace = File(diag, name!!)
        assertTrue("the trace kept in diag", trace.isFile)
        assertTrue("the trace is of that process", trace.readText().contains("pid $pid"))
    }

    @Test
    fun theForceStopThatEndedAnEarlierRunIsToldAsUserRequested() {
        // am instrument force-stops the app when a run ends, so the history keeps one from an earlier run. This test cannot
        // make that force-stop itself (it would end the test process), and installing the app again clears the history:
        // with none there, it has nothing to check. A real force-stop told to the rider is proven end to end by
        // scripts/emu/forcar_parada.py (anExplicitForceStopIsToldByTheNextProjection) and its wording by ExitReasonsTest.
        val history = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        val info = history.firstOrNull { "stop ${context.packageName}" in it.description.orEmpty() }
        assumeTrue(
            "no force-stop from an earlier run in the history (a fresh install has none): ${history.map { "${it.pid} ${it.reason} ${it.description}" }}",
            info != null,
        )
        val line = ExitReasons.describe(ExitHistory.pastExit(info!!))
        assertTrue(line, line.contains("user requested (10)") && line.contains("stop ${context.packageName}"))
    }

    /** Run only by scripts/emu/forcar_parada.py, which force-stops the app with `:exittest` up and passes its pid. */
    @Test
    fun anExplicitForceStopIsToldByTheNextProjection() {
        val pid = InstrumentationRegistry.getArguments().getString("forceStoppedPid")?.toInt()
        assumeTrue("only with scripts/emu/forcar_parada.py", pid != null)
        awaitRecorded(pid!!)

        val line = exitLine(projectionLog(), pid)
        assertNotNull("the exit of pid $pid told", line)
        assertTrue(line, line!!.contains("user requested (10)") && line.contains("stop ${context.packageName}"))
    }

    private fun exitTestProcess(): RunningAppProcessInfo? =
        manager.runningAppProcesses.orEmpty().firstOrNull { it.processName == "${context.packageName}:exittest" }

    private fun startExitTestProcess(): Int {
        context.startActivity(
            Intent().setComponent(ComponentName(context.packageName, "com.reau.app.debug.ExitTestActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        await("the :exittest process up") { exitTestProcess() != null }
        return exitTestProcess()!!.pid
    }

    private fun awaitRecorded(pid: Int, timeoutMs: Long = 15_000) =
        await("Android kept the exit of pid $pid", timeoutMs) {
            manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).isNotEmpty()
        }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /**
     * Runs a projection with no sides and returns its part of the log. The log file
     * lives as long as the process and the projection is marked on and off inside it: the part after the last mark.
     */
    private fun projectionLog(): String {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        val file = diag.listFiles { f -> f.name.startsWith("ride-") }!!.maxByOrNull { it.lastModified() }!!
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the projection marked off", 5_000) { "projeção desligada" in file.readText() || "session end" in file.readText() }
        return file.readText().substringAfterLast("---- projeção ligada")
    }

    private fun exitLine(log: String, pid: Int): String? = log.lines().firstOrNull { "past exit: " in it && "pid $pid," in it }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(what, condition())
    }
}
