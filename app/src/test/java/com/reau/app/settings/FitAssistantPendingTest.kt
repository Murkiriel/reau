package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.video.FrameFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the first-time fit assistant of the "Tela do Android Auto" opens by itself (13/11/2026): only on a phone that never saved a fit, until
 * it is finished or skipped, or a fit of the rider's own is saved. A phone that already has a fit stored (the owner's own, and every phone
 * that saved any setting before this) is never asked, and reads exactly the fit it had.
 */
class FitAssistantPendingTest {
    private val mark = "fit_assistant_done_20261113"

    /** The owner's phone as a version before the assistant left it: everything saved, with a fit of its own, and no mark. */
    private val ownersFit = REauSettings(
        density = 180, fit = Fit.CROP, cropPercent = 70, cropX = 12, cropY = 40, moveX = 3, moveY = -4,
        navFit = FitParams(Fit.CROP, 74, 90, 62, 0, 0), menuFit = FitParams(Fit.WHOLE_VISIBLE, 88, 30, 22, 18, 2),
        menuFitKept = FitParams(Fit.WHOLE_VISIBLE, 88, 30, 22, 18, 2),
    )

    private fun savedBefore(settings: REauSettings) = KeystoreBackedPrefsTest.FakePrefs().also { p ->
        SettingsStore(p).save(settings)
        p.edit().remove(mark).apply()
    }

    @Test
    fun `a phone with nothing saved is asked, and asking writes nothing`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        assertTrue(store.fitAssistantPending())
        store.load()
        assertTrue(store.fitAssistantPending())
        assertTrue("neither reading the settings nor asking writes: ${p.map.keys}", p.map.isEmpty())
    }

    @Test
    fun `a phone that saved a fit before the assistant existed is never asked`() {
        for (settings in listOf(ownersFit, REauSettings(), REauSettings(density = 200))) {
            val p = savedBefore(settings)
            assertFalse("no mark yet", p.contains(mark))
            assertFalse(SettingsStore(p).fitAssistantPending())
            assertFalse("asking writes nothing", p.contains(mark))
        }
    }

    @Test
    fun `that phone reads exactly the fit it had, and keeps it and its answer through a load and a save`() {
        val p = savedBefore(ownersFit)
        val before = HashMap(p.map)
        val store = SettingsStore(p)
        val loaded = store.load()
        assertEquals(ownersFit.sanitized(), loaded)
        assertEquals(ownersFit.appsFit, loaded.appsFit)
        assertEquals(ownersFit.navFit, loaded.navFit)
        assertEquals(ownersFit.menuFit, loaded.menuFit)
        assertEquals("reading changes nothing stored", before, HashMap(p.map))
        store.save(loaded.copy(dashWeather = false))
        assertFalse(SettingsStore(p).fitAssistantPending())
        assertEquals("the save marks it as never to be asked", true, p.map[mark])
        assertEquals(ownersFit.sanitized().copy(dashWeather = false), SettingsStore(p).load())
    }

    @Test
    fun `any one of the three fits stored is a fit saved`() {
        for (key in listOf("fit", "nav_fit", "menu_fit")) {
            val p = KeystoreBackedPrefsTest.FakePrefs()
            p.edit().putString(key, "CROP").apply()
            assertFalse(key, SettingsStore(p).fitAssistantPending())
        }
        val other = KeystoreBackedPrefsTest.FakePrefs()
        other.edit().putInt("density", 200).putBoolean("dash_mask", false).apply()
        assertTrue("a setting that is not a fit does not count", SettingsStore(other).fitAssistantPending())
    }

    @Test
    fun `a new install that saves something else, with the factory fit, is still asked`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.save(store.load().copy(dashWeather = false, density = 200, dashMask = false))
        assertTrue("the fit is stored now, as every save does", p.contains("fit"))
        assertTrue(SettingsStore(p).fitAssistantPending())
        store.save(store.load().copy(infoRing = true))
        assertTrue("and after a second save", SettingsStore(p).fitAssistantPending())
    }

    @Test
    fun `a new install that saves a fit of its own is not asked any more`() {
        val changes: List<(REauSettings) -> REauSettings> = listOf(
            { it.copy(navFit = it.navFit.copy(cropX = 91)) },
            { it.copy(navFit = it.navFit.copy(cropPercent = 73)) },
            { it.copy(cropPercent = 65) },
            { it.copy(fit = Fit.CROP) },
            { it.copy(moveY = 1) },
            { it.copy(zoom = 110) },
            { it.copy(cover = FrameFit.Cover(top = 4)) },
            { it.withMenuFitOwn(false) },
            { it.copy(menuFit = it.menuFitKept.copy(moveX = 21), menuFitKept = it.menuFitKept.copy(moveX = 21)) },
        )
        for ((n, change) in changes.withIndex()) {
            val p = KeystoreBackedPrefsTest.FakePrefs()
            val store = SettingsStore(p)
            store.save(store.load().copy(dashWeather = false))
            assertTrue("change $n: asked before", store.fitAssistantPending())
            store.save(change(store.load()))
            assertFalse("change $n", SettingsStore(p).fitAssistantPending())
            // Going back to the factory fit afterwards does not bring the assistant back.
            store.save(REauSettings())
            assertFalse("change $n, back at the factory fit", SettingsStore(p).fitAssistantPending())
        }
    }

    @Test
    fun `finished or skipped, it is not asked again, whatever is saved afterwards`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.fitAssistantSeen()
        assertFalse(store.fitAssistantPending())
        assertEquals("only the mark is written", setOf(mark), p.map.keys)
        store.save(store.load())
        store.save(store.load().copy(dashWeather = false))
        assertFalse(SettingsStore(p).fitAssistantPending())
    }

    @Test
    fun `seen after a first save, it is not asked again either`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.save(store.load().copy(dashWeather = false))
        assertTrue(store.fitAssistantPending())
        store.fitAssistantSeen()
        assertFalse(store.fitAssistantPending())
        store.save(store.load())
        assertFalse(SettingsStore(p).fitAssistantPending())
    }

    @Test
    fun `the assistant's mark is not a setting, the settings read the same with it and without it`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        val before = store.load()
        store.fitAssistantSeen()
        assertEquals(before, SettingsStore(p).load())
        assertEquals(REauSettings().sanitized(), before)
    }
}
