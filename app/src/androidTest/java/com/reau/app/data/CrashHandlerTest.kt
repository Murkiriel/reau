package com.reau.app.data

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ProjectionSides
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The app crashing for real: a debug-only activity in a process of its own
 * (`:crashtest`) opens a ride log and throws. Before the process dies, the crash file and the ride-log line must be
 * written; the next projection tells "the app crashed last time" once.
 */
@RunWith(AndroidJUnit4::class)
class CrashHandlerTest {
    private val originalSides = ProjectionService.sidesFactory

    /**
     * One crash for the whole class, before its tests: Android marks a process that crashes twice within a minute as
     * "bad" and stops delivering broadcasts to it, so each test crashing on its own would not work.
     */
    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val diag = File(context.filesDir, "diag")
        private val lastCrash = File(diag, "crash-test-last")
        private val marker = "marker-${System.nanoTime()}"
        private const val ANDROID_CRASH_INTERVAL_MS = 62_000L

        fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(what, condition())
        }

        fun crashFile(): File? = diag.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.firstOrNull { marker in it.readText() }

        private fun crashTestProcessAlive(): Boolean =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any { it.processName.endsWith(":crashtest") }

        @JvmStatic
        @BeforeClass
        fun crashOnce() {
            diag.mkdirs()
            diag.listFiles { f -> f.name.startsWith("crash-") && f.name != lastCrash.name }?.forEach { it.delete() }
            // A crash of an earlier run less than a minute ago would make this one the "second" for Android.
            val since = System.currentTimeMillis() - runCatching { lastCrash.readText().trim().toLong() }.getOrDefault(0L)
            if (since < ANDROID_CRASH_INTERVAL_MS) Thread.sleep(ANDROID_CRASH_INTERVAL_MS - since)
            lastCrash.writeText(System.currentTimeMillis().toString())
            context.startActivity(
                Intent().setComponent(ComponentName(context.packageName, "com.reau.app.debug.CrashTestActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("message", marker),
            )
            await("the crash file") { crashFile() != null }
            await("the crashed process gone") { !crashTestProcessAlive() }
        }
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
    }

    @Test
    fun aCrashLeavesItsFileWithTheWholeStackAndTheProcessStillDies() {
        assertTrue("the crash file", crashFile() != null)
        val text = crashFile()!!.readText()
        assertTrue(text, text.contains("thread: main"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: crash test: $marker"))
        assertTrue("where it was thrown: $text", text.contains("CrashTestActivity.onCreate"))
    }

    @Test
    fun theDyingProcessWritesTheCrashIntoItsRideLogBeforeItGoes() {
        val logs = diag.listFiles { f -> f.name.startsWith("ride-") }.orEmpty()
        assertTrue(
            "a ride log with the crash line among ${logs.map { it.name }}",
            // Android wraps what the activity threw ("Unable to start activity …: java.lang.IllegalStateException: …").
            logs.any { log -> log.readLines().any { "the app crashed: " in it && "IllegalStateException: crash test: $marker" in it } },
        )
    }

    @Test
    fun theNextProjectionTellsTheCrashOnce() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        repeat(2) { round ->
            ProjectionService.start(context)
            await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
            val file = diag.listFiles { f -> f.name.startsWith("ride-") }!!.maxByOrNull { it.lastModified() }!!
            ProjectionService.stop(context)
            await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
            // The log lives as long as the process; this projection's part is after its last mark.
            await("the projection marked off", 5_000) { "projeção desligada" in file.readText() || "session end" in file.readText() }
            val part = file.readText().substringAfterLast("---- projeção ligada")
            val told = "the app crashed last time" in part && marker in part
            if (round == 0) assertTrue("told on the first projection: $part", told) else assertFalse("not again", told)
        }
    }
}
