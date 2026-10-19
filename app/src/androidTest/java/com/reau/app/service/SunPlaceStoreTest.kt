package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The place for the sun is kept only with "Usar a localização" on, and forgotten when it goes
 * off; the switch off, nothing kept is used.
 */
@RunWith(AndroidJUnit4::class)
class SunPlaceStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() = SunPlace.forget(context)

    @Test
    fun aKeptPlaceIsUsedOnlyWithTheSwitchOn() {
        SunPlace.save(context, DayNight.Place(-10.1, -50.6))
        assertEquals(DayNight.Place(-10.1, -50.6), SunPlace.saved(context, useLocation = true))
        assertNull(SunPlace.saved(context, useLocation = false))
    }

    @Test
    fun theSwitchOffForgetsThePlace() {
        SunPlace.save(context, DayNight.Place(-10.1, -50.6))
        PhonePlace.learn(context, useLocation = false)
        assertNull(SunPlace.saved(context, useLocation = true))
    }
}
