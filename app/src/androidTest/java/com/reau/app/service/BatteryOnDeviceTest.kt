package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.ui.BatteryWatch
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Battery saver matters (another app dropped under it), on the emulator's real Android: what REau reads of
 * battery saver and of its exemption, and the projection under battery saver with the screen off and the phone forced
 * into deep sleep (the emulator never suspends its processor: only the rules Android applies are proven here, not
 * the phone itself).
 */
@RunWith(AndroidJUnit4::class)
class BatteryOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val originalSides = ProjectionService.sidesFactory

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(what, condition())
    }

    /**
     * Battery saver on, off the charger. Android takes the unplug a moment later and, on the charger, refuses battery
     * saver: the switch is asked again until it holds.
     */
    private fun saverOn() {
        shell("dumpsys battery unplug")
        val deadline = System.currentTimeMillis() + 10_000
        while (!BatteryWatch.read(context).saverOn && System.currentTimeMillis() < deadline) {
            shell("cmd power set-mode 1")
            Thread.sleep(300)
        }
    }

    @After
    fun tearDown() {
        shell("dumpsys deviceidle unforce")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("cmd power set-mode 0")
        shell("dumpsys battery reset")
        shell("dumpsys deviceidle whitelist -${context.packageName}")
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
    }

    @Test
    fun batterySaverIsReadAsAndroidSwitchesIt() {
        saverOn()
        await("saver on read") { BatteryWatch.read(context).saverOn }
        shell("cmd power set-mode 0")
        await("saver off read") { !BatteryWatch.read(context).saverOn }
    }

    @Test
    fun theExemptionIsReadAsAndroidGivesIt() {
        shell("dumpsys deviceidle whitelist -${context.packageName}")
        await("not exempt") { !BatteryWatch.read(context).exempt }
        shell("dumpsys deviceidle whitelist +${context.packageName}")
        await("exempt") { BatteryWatch.read(context).exempt }
    }

    @Test
    fun theProjectionKeepsItsServiceAndTheNetworkUnderBatterySaverWithTheScreenOff() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        shell("dumpsys deviceidle whitelist -${context.packageName}") // the worst case: no exemption
        saverOn()
        shell("input keyevent KEYCODE_SLEEP")
        shell("dumpsys deviceidle force-idle")
        await("saver on") { BatteryWatch.read(context).saverOn }

        Thread.sleep(SCREEN_OFF_MS)

        assertEquals("still running", Phase.RUNNING, ProjectionService.projection.value.phase)
        val services = shell("dumpsys activity services ${context.packageName}")
        assertTrue("the service is still in the foreground:\n${services.take(2_000)}", "isForeground=true" in services)
        // Battery saver's firewall cuts the network of apps in the background; the projection's service keeps it.
        val reached = runCatching { Socket().use { it.connect(InetSocketAddress(NETWORK_PROBE, 53), 5_000) } }
        assertTrue("network under battery saver and deep sleep: ${reached.exceptionOrNull()}", reached.isSuccess)
    }

    private companion object {
        const val SCREEN_OFF_MS = 60_000L
        const val NETWORK_PROBE = "8.8.8.8"
    }
}
