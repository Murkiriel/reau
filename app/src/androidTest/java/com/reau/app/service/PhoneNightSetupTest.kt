package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.settings.MapTheme
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the phone's setup gives for day/night and for the thermal status. */
@RunWith(AndroidJUnit4::class)
class PhoneNightSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() = SettingsStore.open(context).save(REauSettings())

    @Test
    fun theMapThemeOfTheSettingsDecides() {
        SettingsStore.open(context).save(REauSettings(mapTheme = MapTheme.NIGHT))
        assertEquals(true, LiveSides.Setup.forPhone(context).night())
        SettingsStore.open(context).save(REauSettings(mapTheme = MapTheme.DAY))
        assertEquals(false, LiveSides.Setup.forPhone(context).night())
    }

    @Test
    fun byTheSunItAnswersWithoutALocationPermission() {
        SettingsStore.open(context).save(REauSettings(mapTheme = MapTheme.AUTO))
        val setup = LiveSides.Setup.forPhone(context)
        // No location permission is granted to the app here: the time zone stands in. It must answer, not throw.
        setup.night()
        assertEquals("asked once a minute", 60_000L, setup.nightCheckMs)
    }

    @Test
    fun theThermalStatusIsThePhonesOwn() {
        val status = LiveSides.Setup.forPhone(context).thermalStatus()
        assertTrue("got $status", status in 0..6)
    }
}
