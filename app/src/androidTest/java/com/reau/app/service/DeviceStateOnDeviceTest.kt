package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.data.RideDiagnostics
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the emulator: the phone's state is read with plausible
 * values and follows `dumpsys battery` and `cmd power set-mode`; with the projection on, the `device …` line goes to
 * the ride log.
 */
@RunWith(AndroidJUnit4::class)
class DeviceStateOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val reader = DeviceStateReader(context) { null }
    private val originalSides = ProjectionService.sidesFactory

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(what, condition())
    }

    @After
    fun tearDown() {
        shell("dumpsys battery reset")
        shell("cmd power set-mode 0")
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
    }

    @Test
    fun theStateIsReadWithPlausibleValues() {
        val s = reader.read()
        assertTrue("battery: $s", s.batteryPct in 0..100)
        assertTrue("charger known: $s", s.charging != null)
        assertTrue("temperature: $s", s.batteryTempC != null && s.batteryTempC!! in -20f..80f)
        assertTrue("voltage: $s", s.batteryMv != null && s.batteryMv!! in 2_000..6_000)
        assertTrue("thermal: $s", s.thermal in 0..6)
        assertTrue("memory: $s", s.appMaxMb > 0 && s.appUsedMb in 1..s.appMaxMb && (s.phoneAvailMb ?: 0) > 0)
        assertTrue("screen, doze, saver, exemption known: $s",
            listOf(s.screenOn, s.interactive, s.dozing, s.powerSave, s.batteryExempt, s.lowMemory).all { it != null })
        assertTrue("no bike Wi-Fi given: $s", s.bikeWifi == null)
    }

    @Test
    fun theBatteryFollowsDumpsys() {
        shell("dumpsys battery set level 42")
        await("level 42") { reader.read().batteryPct == 42 }
        shell("dumpsys battery unplug")
        await("unplugged") { reader.read().charging == false }
        shell("dumpsys battery set ac 1")
        await("on the charger") { reader.read().charging == true }
    }

    @Test
    fun theSaverFollowsCmdPower() {
        // On the charger (an emulator after a cold boot) Android refuses the saver; tearDown plugs back.
        shell("dumpsys battery unplug")
        // Android takes the unplug a moment later and refuses battery saver until then: ask again until it holds.
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val deadline = System.currentTimeMillis() + 10_000
        while (!power.isPowerSaveMode && System.currentTimeMillis() < deadline) {
            shell("cmd power set-mode 1")
            Thread.sleep(300)
        }
        await("saver on") { reader.read().powerSave == true }
        shell("cmd power set-mode 0")
        await("saver off") { reader.read().powerSave == false }
    }

    @Test
    fun withTheProjectionOnTheDeviceLineGoesToTheRideLog() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        RideDiagnostics.startProcess("test")
        val diag = File(context.filesDir, "diag")
        shell("dumpsys battery unplug")
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        shell("dumpsys battery set level 37")
        shell("dumpsys battery set ac 1") // a change of charger: a line at once
        val newest = { diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() } }
        await("the start line and the charger change") {
            val text = newest()?.readText().orEmpty().substringAfterLast("projeção ligada")
            "[device] device battery=" in text && " [start]" in text && "battery=37%" in text && "[change: charging]" in text
        }
    }
}
