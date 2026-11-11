package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The experimental guard of the start: a process that ended within 10 s of a start made with experimental switches on leaves them off.
 * With the guard off (the default) no mark is left and nothing is ever switched off.
 */
class ExperimentsGuardTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val mark: File get() = ExperimentsGuard.mark(folder.root)
    private val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
    private val experiments = REauSettings(experimentRtpPacing = true, experimentEncoderWatch = true)
    private val guarded = experiments.copy(experimentStartGuard = true)

    @Test
    fun `the guard is off by default, and off no start leaves a mark`() {
        assertFalse(REauSettings().experimentStartGuard)
        assertNull(ExperimentsGuard.projectionStarted(experiments, mark, nowMs = 1))
        assertFalse(mark.exists())
    }

    @Test
    fun `with the guard off a process start switches nothing off, even with a mark left from before`() {
        store.save(experiments)
        mark.writeText("5")
        assertNull(ExperimentsGuard.atProcessStart(store, mark))
        assertEquals("the stored settings are as they were", experiments, store.load())
        assertFalse("the old mark goes", mark.exists())
    }

    @Test
    fun `with the guard on and no experiment on there is nothing to watch`() {
        assertFalse(ExperimentsGuard.armed(REauSettings(experimentStartGuard = true)))
        assertNull(ExperimentsGuard.projectionStarted(REauSettings(experimentStartGuard = true), mark, nowMs = 1))
        assertFalse(mark.exists())
    }

    @Test
    fun `a watched start leaves the mark, and the mark goes when the start survived`() {
        val token = ExperimentsGuard.projectionStarted(guarded, mark, nowMs = 1_000)
        assertNotNull(token)
        assertTrue(mark.exists())
        ExperimentsGuard.settled(mark, token)
        assertFalse(mark.exists())
        store.save(guarded)
        assertNull("the next process finds no mark", ExperimentsGuard.atProcessStart(store, mark))
        assertEquals(guarded, store.load())
    }

    @Test
    fun `a process that finds the mark switches every experiment off, keeps the guard and the rest, and says which were on`() {
        store.save(guarded.copy(density = 200, experimentWifiBand = true))
        ExperimentsGuard.projectionStarted(store.load(), mark, nowMs = 1_000)
        val line = ExperimentsGuard.atProcessStart(store, mark)
        assertEquals(
            "start guard (experiment): the app ended within 10 s of a start made with experimental switches on " +
                "(experimentRtpPacing, experimentWifiBand, experimentEncoderWatch): they were switched off; the guard stays on",
            line,
        )
        val after = store.load()
        assertEquals(emptyList<String>(), ExperimentsGuard.experimentsOn(after))
        assertTrue(after.experimentStartGuard)
        assertEquals("nothing else is touched", 200, after.density)
        assertFalse("the mark is used once", mark.exists())
        assertNull(ExperimentsGuard.atProcessStart(store, mark))
    }

    @Test
    fun `every experimental switch but the guard is one the guard knows and switches off`() {
        val names = REauSettings::class.java.declaredFields.map { it.name }.filter { it.startsWith("experiment") && it != "experimentStartGuard" }
        val allOn = REauSettings(
            experimentRtpPacing = true, experimentDebugDscp = true, experimentAdaptiveBitrate = true, experimentWifiBind = true,
            experimentWifiBand = true, experimentEncoderWatch = true, experimentFocusGap = true, experimentNightMemory = true,
            experimentStartGuard = true,
        )
        assertEquals("a new experimental switch needs its line in the guard", names.sorted(), ExperimentsGuard.experimentsOn(allOn).sorted())
        assertEquals(REauSettings(experimentStartGuard = true), ExperimentsGuard.allOff(allOn))
    }

    @Test
    fun `the mark of a newer start is not taken away by an older one's end`() {
        val first = ExperimentsGuard.projectionStarted(guarded, mark, nowMs = 1_000)
        val second = ExperimentsGuard.projectionStarted(guarded, mark, nowMs = 5_000)
        ExperimentsGuard.settled(mark, first)
        assertTrue("the second start is still watched", mark.exists())
        ExperimentsGuard.settled(mark, second)
        assertFalse(mark.exists())
        ExperimentsGuard.settled(mark, null)
    }

    @Test
    fun `the app asks the guard before it reads the settings, and the projection leaves and clears the mark`() {
        val app = File("src/main/java/com/reau/app/REauApp.kt").readText()
        val ask = app.indexOf("ExperimentsGuard.atProcessStart(SettingsStore.open(this), com.reau.app.settings.ExperimentsGuard.mark(filesDir))")
        assertTrue(ask in 0 until app.indexOf("val settings = SettingsStore.open(this).load()"))
        assertTrue(app.contains("guardLine?.let { RideDiagnostics.warn(\"settings\", it) }"))
        val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
        assertTrue(service.contains("watchTheStart(inUse)"))
        assertTrue(service.contains("schedule(ExperimentsGuard.SETTLE_MS) { ExperimentsGuard.settled(mark, token) }"))
        val release = service.substringAfter("override fun release()").substringBefore("override fun startAndroidAuto()")
        assertTrue(release.contains("ExperimentsGuard.settled(ExperimentsGuard.mark(filesDir), guardToken)"))
        assertEquals(10_000L, ExperimentsGuard.SETTLE_MS)
    }
}
