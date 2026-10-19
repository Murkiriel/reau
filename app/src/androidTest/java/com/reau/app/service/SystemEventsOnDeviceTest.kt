package com.reau.app.service

import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.MainActivity
import com.reau.app.data.RideDiagnostics
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the emulator: each event provoked from the shell
 * (`input keyevent`, `dumpsys deviceidle force-idle`, `dumpsys battery`, `cmd power`, `svc wifi`) and the app going to
 * the background and back reach the ride log as `[system] …` lines (SystemEvents is started by REauApp; the
 * instrumentation runs in the app's process). The projection is on, as on the bike: without a foreground service the
 * test process is frozen with the screen off and Android hands it the broadcasts late (seen in this test's runs).
 */
@RunWith(AndroidJUnit4::class)
class SystemEventsOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val diag = File(context.filesDir, "diag")
    private lateinit var marker: String
    private val originalSides = ProjectionService.sidesFactory

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    @Before
    fun setUp() {
        SystemEvents.start(context) // a no-op when REauApp already started it
        RideDiagnostics.startProcess("test")
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        shell("input keyevent KEYCODE_WAKEUP")
        marker = "system-events-test-${System.nanoTime()}"
        RideDiagnostics.logAndWait("test", marker)
    }

    @After
    fun tearDown() {
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        shell("cmd power set-mode 0")
        shell("svc wifi enable")
        shell("input keyevent KEYCODE_WAKEUP")
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(what, condition())
    }

    /** The lines of the ride log written since this test's marker. */
    private fun since(): String {
        val newest = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() } ?: return ""
        return newest.readText().substringAfterLast(marker, "")
    }

    private fun awaitLineWhere(what: String, timeoutMs: Long = 15_000, matches: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (since().lines().any(matches)) return
            Thread.sleep(200)
        }
        assertTrue("$what not in the log since the marker:\n${since()}", false)
    }

    private fun awaitLine(line: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (since().lines().any { it.endsWith("[system] $line") }) return
            Thread.sleep(200)
        }
        assertTrue("[system] $line not in the log since the marker:\n${since()}", false)
    }

    @Test
    fun theScreenOffAndOn() {
        shell("input keyevent KEYCODE_SLEEP")
        awaitLine("screen off")
        shell("input keyevent KEYCODE_WAKEUP")
        awaitLine("screen on")
    }

    @Test
    fun theChargerOutAndInAndTheLowBattery() {
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery unplug")
        awaitLine("charger out")
        shell("dumpsys battery set level 4")
        // The line carries the level (and the battery saver's mark when Android lets REau read it).
        awaitLineWhere("battery low at 4%") { it.contains("[system] battery low at 4%") }
        // Back to full: Android sends "battery okay" only above its low mark plus a margin, and on a phone with the battery
        // saver's automatic level at 75% that mark is 75%, so a level like 60 would never be "okay" there.
        shell("dumpsys battery set level 100")
        shell("dumpsys battery set ac 1")
        awaitLine("charger in")
        awaitLine("battery okay")
    }

    @Test
    fun theSaverOnAndOff() {
        // On the charger (an emulator after a cold boot) Android refuses the saver; tearDown plugs back.
        shell("dumpsys battery unplug")
        // Android takes the unplug a moment later and refuses battery saver until then: ask again until it holds.
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val deadline = System.currentTimeMillis() + 10_000
        while (!power.isPowerSaveMode && System.currentTimeMillis() < deadline) {
            shell("cmd power set-mode 1")
            Thread.sleep(300)
        }
        awaitLine("saver on")
        shell("cmd power set-mode 0")
        awaitLine("saver off")
    }

    @Test
    fun dozeOnAndOff() {
        shell("dumpsys battery unplug")
        shell("input keyevent KEYCODE_SLEEP")
        shell("dumpsys deviceidle force-idle")
        awaitLine("doze on")
        shell("dumpsys deviceidle unforce")
        shell("input keyevent KEYCODE_WAKEUP")
        awaitLine("doze off")
    }

    @Test
    fun theDataNetworkLostAndBack() {
        shell("svc wifi disable")
        awaitLine("data network available: cell", timeoutMs = 30_000)
        shell("svc wifi enable")
        awaitLine("data network available: wifi", timeoutMs = 30_000)
    }

    @Test
    fun theAppToTheBackgroundAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            awaitLine("app foreground")
            screen.moveToState(Lifecycle.State.CREATED) // stopped: no screen of the app is shown
            // Rewritten: the line now says how the process stands (before: it ended at
            // "app background"). This test runs with a projection on (its ride log), held in the foreground.
            awaitLineWhere("[system] app background — process importance 125 … projection in the foreground") {
                it.endsWith("[system] app background — process importance 125 (foreground service), projection in the foreground")
            }
        }
    }
}
