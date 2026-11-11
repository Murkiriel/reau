package com.reau.app.service

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun grantTheLocationPermission() {
        // The place kept is used only while the permission is given (SunPlace.saved). Granting only: revoking a runtime permission
        // kills the app's process, the one this test runs in; the revoked case is a JVM test (SunPlaceTest).
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

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
