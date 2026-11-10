package com.reau.app.service

import com.reau.app.data.ThermalHeadroomNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * This build does not ask Android for the thermal headroom (a test the owner asked for on 08/11/2026: the analysis found the phone's dead thermal
 * HAL flooding the system log along with that query, every 5 s). The reader that asks lives in a class that needs Android, so the source is read, as
 * [CompositorBusyIsWiredTest] does. What stays: the thermal status (the fps ceiling's input), the battery temperature (the heat warning's), and the
 * note in the ride log, once per projection, that the headroom is not asked.
 */
class ThermalHeadroomIsNotAskedTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val reader = source("service/DeviceStateReader.kt")
    private val projection = source("service/ProjectionService.kt")

    @Test
    fun `no code in the app asks PowerManager for the thermal headroom`() {
        val askers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && "getThermalHeadroom" in it.readText() }.map { it.name }.toList()
        assertEquals("who still asks for the headroom: $askers", emptyList<String>(), askers)
    }

    @Test
    fun `the reader leaves the headroom unknown and still reads the thermal status and the battery temperature`() {
        assertTrue(reader.contains("headroom = null,"))
        assertTrue(reader.contains("thermal = runCatching { power.currentThermalStatus }.getOrNull(),"))
        assertTrue(reader.contains("EXTRA_TEMPERATURE"))
    }

    @Test
    fun `the thermal ceiling of the frame rate and the heat warning do not depend on the headroom`() {
        assertFalse("the ceiling reads the status only", source("stream/ThermalCeiling.kt").contains("headroom", ignoreCase = true))
        assertFalse("the heat watch reads the battery only", source("service/BatteryHeatWatch.kt").contains("headroom", ignoreCase = true))
        assertTrue("the warning is given by the battery temperature", projection.contains("val temp = state.batteryTempC"))
        assertTrue("the video loop still reads the status", source("service/LiveSides.kt").contains("currentThermalStatus"))
    }

    @Test
    fun `the ride log says once per projection that the headroom is not asked, before the looks start`() {
        val look = projection.substringAfter("private fun lookAtTheDevice() {").substringBefore("private fun warnIfHot")
        assertEquals(1, Regex("ThermalHeadroomNote\\.LINE").findAll(look).count())
        assertTrue("before the first look is scheduled", look.indexOf("ThermalHeadroomNote.LINE") < look.indexOf("deviceLook = worker.scheduleWithFixedDelay"))
        assertEquals("lookAtTheDevice is called from one place, the start of a projection", 2, Regex("lookAtTheDevice\\(\\)").findAll(projection).count())
    }

    @Test
    fun `the note is not taken for a look at the phone, and it says what is left`() {
        val line = ThermalHeadroomNote.LINE
        assertFalse("the reader takes lines that start with device for the looks", line.startsWith("device "))
        assertTrue(line, "not asked" in line && "headroom" in line)
        assertTrue(line, "battery temperature" in line && "thermal status" in line)
    }
}
