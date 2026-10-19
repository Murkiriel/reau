package com.reau.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Covers the "ride logs" kill switch (RideDiagnostics.enabled) and the idempotent-start behavior
 * that lets more than one caller call start() for the same ride without fragmenting it into two
 * files.
 */
@RunWith(AndroidJUnit4::class)
class RideDiagnosticsEnabledTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalEnabled = true
    private lateinit var diagDir: File

    @Before
    fun setUp() {
        RideDiagnostics.init(context)
        originalEnabled = RideDiagnostics.enabled
        // The app opens a process-long file; these tests are about the per-session files of before.
        RideDiagnostics.stopProcess("test setup")
        RideDiagnostics.stop("test setup")
        diagDir = File(context.filesDir, "diag")
        diagDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        RideDiagnostics.stop("test teardown")
        RideDiagnostics.startProcess("test teardown") // back to how the app runs
        RideDiagnostics.enabled = originalEnabled
        diagDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun enabledRoundTripsThroughSharedPreferences() {
        RideDiagnostics.enabled = false
        assertFalse(RideDiagnostics.enabled)
        assertFalse(
            context.getSharedPreferences("ride_diagnostics", android.content.Context.MODE_PRIVATE)
                .getBoolean("enabled", true),
        )

        RideDiagnostics.enabled = true
        assertTrue(RideDiagnostics.enabled)
        assertTrue(
            context.getSharedPreferences("ride_diagnostics", android.content.Context.MODE_PRIVATE)
                .getBoolean("enabled", false),
        )
    }

    @Test
    fun startAndLogAreNoOpsWhileDisabled() {
        RideDiagnostics.enabled = false
        // RideDiagnostics is a real process-wide singleton (by design — see testOnlyActiveFileName's
        // own doc) — asserting an absolute count in the shared diag dir is asserting about every
        // OTHER test class's straggler writes too, not just this one. A before/after delta on the
        // exact narrow window this test's own action could have written in is immune to that.
        val before: Set<String> = diagDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        RideDiagnostics.start("test disabled")
        assertFalse(RideDiagnostics.isSessionActive())

        RideDiagnostics.log("test", "should not be recorded")
        assertFalse(RideDiagnostics.isSessionActive())
        val after: Set<String> = diagDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(before, after)
    }

    @Test
    fun startOpensASessionWhileEnabled() {
        RideDiagnostics.enabled = true

        RideDiagnostics.start("test enabled")
        assertTrue(RideDiagnostics.isSessionActive())
        // Check THIS session's own file exists, not the shared dir's total count — see
        // testOnlyActiveFileName's own doc for why a raw count is fragile in this shared singleton.
        val activeName = RideDiagnostics.testOnlyActiveFileName()
        assertTrue(diagDir.listFiles()?.any { it.name == activeName } == true)

        RideDiagnostics.stop("test done")
        assertFalse(RideDiagnostics.isSessionActive())
    }

    @Test
    fun startIsIdempotentAndDoesNotFragmentOneRideIntoTwoFiles() {
        RideDiagnostics.enabled = true

        RideDiagnostics.start("first start (e.g. setDestination)")
        val firstFile = RideDiagnostics.testOnlyActiveFileName()
        assertTrue(firstFile != null)

        // A second start() for the same ride (e.g. connect() dialing the dash shortly after) must
        // reuse the already-open session file instead of opening a new one. Asserted against
        // RideDiagnostics's own file identity (not a directory listing size) — see
        // testOnlyActiveFileName's own doc for why: a raw count in the shared diag dir can be
        // polluted by an unrelated straggler write from another test class's not-yet-fully-
        // cancelled coroutines, which is exactly what made this assertion nondeterministically
        // fail in a full suite run (confirmed live) despite this behavior being correct.
        RideDiagnostics.start("second start (e.g. connect)")
        assertEquals(firstFile, RideDiagnostics.testOnlyActiveFileName())

        RideDiagnostics.stop("done")
    }
}
