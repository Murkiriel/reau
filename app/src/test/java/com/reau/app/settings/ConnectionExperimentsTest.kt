package com.reau.app.settings

import com.reau.app.dash.DashConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four experimental switches of the connection: off by default (the app then does what it did before them), kept as stored, never
 * brought back by [REauSettings.sanitized], and said in the settings line of the ride log.
 */
class ConnectionExperimentsTest {
    private val allOn = REauSettings(
        experimentRtpPacing = true, experimentDebugDscp = true, experimentAdaptiveBitrate = true, experimentWifiBind = true,
    )

    @Test
    fun `all four are off by default`() {
        val s = REauSettings()
        assertEquals(listOf(false, false, false, false), listOf(s.experimentRtpPacing, s.experimentDebugDscp, s.experimentAdaptiveBitrate, s.experimentWifiBind))
    }

    @Test
    fun `sanitized keeps them, in a debug build and in a release, and keeps the profile's own values fixed`() {
        for (debug in listOf(true, false)) {
            val s = allOn.sanitized(debug)
            assertTrue(s.experimentRtpPacing && s.experimentDebugDscp && s.experimentAdaptiveBitrate && s.experimentWifiBind)
            assertEquals("the stream's fixed profile is not touched", listOf(4, 400_000, 1), listOf(s.fpsCeiling, s.streamBitrateBps, s.keyframeSec))
        }
    }

    @Test
    fun `the bitrate is the profile's 400 kbps with the experiment off and 200 kbps with it on`() {
        assertEquals(400_000, REauSettings().effectiveStreamBitrateBps)
        assertEquals(DashConfig.PROFILE_FULL_BPS, REauSettings().sanitized().effectiveStreamBitrateBps)
        assertEquals(200_000, REauSettings(experimentAdaptiveBitrate = true).sanitized().effectiveStreamBitrateBps)
        assertEquals(DashConfig.EXPERIMENT_TARGET_BPS, REauSettings(experimentAdaptiveBitrate = true).effectiveStreamBitrateBps)
        assertTrue("the other three leave the bitrate alone", allOn.copy(experimentAdaptiveBitrate = false).effectiveStreamBitrateBps == 400_000)
    }

    @Test
    fun `they are stored and read back, each on its own`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        store.save(REauSettings(experimentDebugDscp = true))
        val one = store.load()
        assertEquals(listOf(false, true, false, false), listOf(one.experimentRtpPacing, one.experimentDebugDscp, one.experimentAdaptiveBitrate, one.experimentWifiBind))
        store.save(allOn)
        val all = store.load()
        assertTrue(all.experimentRtpPacing && all.experimentDebugDscp && all.experimentAdaptiveBitrate && all.experimentWifiBind)
        store.save(allOn.copy(experimentWifiBind = false))
        assertFalse(store.load().experimentWifiBind)
        assertEquals("a bitrate stored by the profile stays 400 kbps with the experiment on", 400_000, store.load().streamBitrateBps)
    }

    @Test
    fun `the four later ones are off by default, kept by sanitized, stored each on its own and said in the log`() {
        val none = REauSettings()
        assertFalse(none.experimentWifiBand || none.experimentEncoderWatch || none.experimentFocusGap)
        assertTrue("the guard comes on by default", none.experimentStartGuard)
        val later = REauSettings(experimentWifiBand = true, experimentStartGuard = true, experimentEncoderWatch = true, experimentFocusGap = true)
        for (debug in listOf(true, false)) assertEquals(later.sanitized(debug), later.sanitized(debug).copy(
            experimentWifiBand = true, experimentStartGuard = true, experimentEncoderWatch = true, experimentFocusGap = true,
        ))
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        val each = listOf<(REauSettings) -> Boolean>(
            { it.experimentWifiBand }, { it.experimentStartGuard }, { it.experimentEncoderWatch }, { it.experimentFocusGap },
        )
        val off = REauSettings(experimentStartGuard = false)
        val ones = listOf(
            off.copy(experimentWifiBand = true), off.copy(experimentStartGuard = true),
            off.copy(experimentEncoderWatch = true), off.copy(experimentFocusGap = true),
        )
        for ((i, one) in ones.withIndex()) {
            store.save(one)
            assertEquals("switch $i alone", List(4) { it == i }, each.map { it(store.load()) })
            assertFalse("the first four stay off", store.load().let { it.experimentRtpPacing || it.experimentDebugDscp || it.experimentAdaptiveBitrate || it.experimentWifiBind })
        }
        val line = SettingsInUse.line(later)
        assertTrue(line, "experimentWifiBand=true experimentStartGuard=true experimentEncoderWatch=true experimentFocusGap=true" in line)
        val said = SettingsInUse.experimentsLine(later)!!
        for (part in listOf("2.4 GHz", "mute encoder watch", "400 ms focus gap")) assertTrue("$part in: $said", part in said)
        assertNull(SettingsInUse.experimentsLine(none))
        assertNull("the guard, on by default, is no experiment on", SettingsInUse.experimentsLine(REauSettings(experimentStartGuard = true)))
        assertFalse("and it is not said among the others", "start guard" in said)
        // The ninth: day and night with memory.
        assertFalse(none.experimentNightMemory)
        store.save(off.copy(experimentNightMemory = true))
        assertTrue(store.load().experimentNightMemory)
        assertFalse("alone", each.any { it(store.load()) })
        assertTrue(REauSettings(experimentNightMemory = true).sanitized().experimentNightMemory)
        assertTrue("day and night remembered" in SettingsInUse.experimentsLine(REauSettings(experimentNightMemory = true))!!)
    }

    @Test
    fun `a phone that never stored them reads them off`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 200).apply()
        val s = SettingsStore(prefs).load()
        assertFalse(s.experimentRtpPacing || s.experimentDebugDscp || s.experimentAdaptiveBitrate || s.experimentWifiBind)
    }

    @Test
    fun `the settings line of the ride log says each one, and a line says the ones that are on`() {
        val off = SettingsInUse.line(REauSettings())
        assertTrue(off, "experimentRtpPacing=false experimentDebugDscp=false experimentAdaptiveBitrate=false experimentWifiBind=false" in off)
        val on = SettingsInUse.line(allOn)
        assertTrue(on, "experimentRtpPacing=true experimentDebugDscp=true experimentAdaptiveBitrate=true experimentWifiBind=true" in on)
        assertNull("nothing is said when none is on", SettingsInUse.experimentsLine(REauSettings()))
        val said = SettingsInUse.experimentsLine(allOn)!!
        assertTrue(said, said.startsWith("connection experiments ON: "))
        for (part in listOf("video pacing", "priority marks", "adaptive bitrate", "bind retries")) assertTrue("$part in: $said", part in said)
        val one = SettingsInUse.experimentsLine(REauSettings(experimentDebugDscp = true))!!
        assertTrue(one, "priority marks" in one && "video pacing" !in one && "adaptive bitrate" !in one && "bind retries" !in one)
    }

    @Test
    fun `a change of one of them is said in the log as a setting change`() {
        val changes = REauSettings().changesTo(REauSettings(experimentRtpPacing = true))
        assertEquals(listOf("experimentRtpPacing: false -> true"), changes)
    }
}
