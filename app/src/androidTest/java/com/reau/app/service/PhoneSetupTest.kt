package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.settings.Fit
import com.reau.app.settings.PlanSettings
import com.reau.app.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The settings the rider saved are what the next projection runs with. */
@RunWith(AndroidJUnit4::class)
class PhoneSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore.open(context)

    @After
    fun tearDown() = store.save(PlanSettings.PLAN)

    @Test
    fun theSavedSettingsReachTheSetupOfTheNextProjection() {
        val saved = PlanSettings.PLAN.copy(
            // The resolution (800×480) and the video clock (0%) are fixed (FixedSettingsTest).
            density = 240, marginW = 200, marginH = 40, fit = Fit.CROP,
            cropPercent = 50, recordVideo = true, recordSessions = true,
        )
        store.save(saved)

        val setup = LiveSides.Setup.forPhone(context)

        // What is loaded is what is fixed too (the maneuver data is always on), not what PLAN had.
        assertEquals(saved.sanitized().aaConfig, setup.aaConfig)
        assertEquals(saved.mapping, setup.mapping)
        // The stream's four values are the fixed profile 1 (FixedProfilesTest).
        assertEquals(4, setup.video.ceilingFps)
        assertEquals(0, setup.video.clockSlowPercent)
        assertEquals(400_000, setup.video.bitrateBps)
        assertEquals(com.reau.app.dash.BitrateMode.CBR, setup.video.bitrateMode)
        assertEquals(1, setup.video.keyframeSec)
        assertTrue("some frames are recorded: ${setup.video.recordFrames}", setup.video.recordFrames > 0)
        // The recordings in the app's internal storage.
        assertEquals(File(context.filesDir, "video"), setup.video.recordDir)
        assertEquals(File(context.filesDir, "sessions"), setup.aaOptions.recording?.dir)
        assertEquals("the receiver keeps its own port", 5277, setup.aaOptions.port)
    }

    @Test
    fun withTheDebugOptionsOffNothingIsRecorded() {
        store.save(PlanSettings.PLAN)
        val setup = LiveSides.Setup.forPhone(context)
        assertEquals(0, setup.video.recordFrames)
        assertNull(setup.aaOptions.recording)
        assertEquals("fixed: profile 1", 4, setup.video.ceilingFps)
        assertEquals(PlanSettings.PLAN.mapping, setup.mapping)
    }
}
