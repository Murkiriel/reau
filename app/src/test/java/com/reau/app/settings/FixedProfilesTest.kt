package com.reau.app.settings

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stream has two profiles and nothing else. Profile 1 is 4 frames a second at
 * 400 kbps, a constant bitrate, a keyframe every second; profile 2 is half of it, 2 frames a second at 200 kbps. The
 * four settings that were choices (frame ceiling, bitrate, mode, keyframe) are fixed to profile 1, whatever a phone
 * had stored before; the navigation profiles are what moves the stream to profile 2 and back.
 */
class FixedProfilesTest {

    @Test
    fun `the defaults are profile 1`() {
        val s = REauSettings()
        assertEquals(4, s.fpsCeiling)
        assertEquals(400_000, s.streamBitrateBps)
        assertEquals(BitrateMode.CBR, s.bitrateMode)
        assertEquals(1, s.keyframeSec)
        assertEquals(true, s.aaNewProtocol)
    }

    @Test
    fun `the constants are the two profiles`() {
        assertEquals(listOf(4, 400_000, 1), listOf(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_FULL_BPS, DashConfig.PROFILE_KEYFRAME_SEC))
        assertEquals(listOf(2, 200_000), listOf(DashConfig.PROFILE_REDUCED_FPS, DashConfig.PROFILE_REDUCED_BPS))
        assertEquals("profile 2 is half of profile 1", DashConfig.PROFILE_FULL_BPS / 2, DashConfig.PROFILE_REDUCED_BPS)
        assertEquals("profile 2 is half of profile 1", DashConfig.PROFILE_FULL_FPS / 2, DashConfig.PROFILE_REDUCED_FPS)
    }

    @Test
    fun `a value set in code is brought back to profile 1`() {
        val s = REauSettings(fpsCeiling = 16, streamBitrateBps = 900_000, bitrateMode = BitrateMode.VBR, keyframeSec = 2).sanitized()
        assertEquals(listOf(4, 400_000, 1), listOf(s.fpsCeiling, s.streamBitrateBps, s.keyframeSec))
        assertEquals(BitrateMode.CBR, s.bitrateMode)
        assertEquals("automatic keyframe is gone too", 1, REauSettings(keyframeSec = 0).sanitized().keyframeSec)
    }

    @Test
    fun `what a phone stored before is read as profile 1`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putBoolean("conservative_video_20261010", true).putInt("fps_ceiling", 12).putInt("stream_bitrate", 1_024_000)
            .putString("bitrate_mode", "VBR").putInt("keyframe_sec", 2).putInt("density", 200).apply()
        val s = SettingsStore(prefs).load()
        assertEquals(listOf(4, 400_000, 1), listOf(s.fpsCeiling, s.streamBitrateBps, s.keyframeSec))
        assertEquals(BitrateMode.CBR, s.bitrateMode)
        assertEquals("the rest stays", 200, s.density)
    }

    @Test
    fun `a phone saved by an older version gets the newer protocol on, and keeps the rest`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("fps_ceiling", 12).putBoolean("aa_slow_pace", true).putInt("aa_slow_pace_fps", 18)
            .putBoolean("aa_new_protocol", false).putInt("density", 200).putBoolean("gemini", false).apply()
        val s = SettingsStore(prefs).load()
        assertEquals(4, s.fpsCeiling)
        assertEquals("protocol 1.7, always", true, s.aaNewProtocol)
        assertEquals("the rest stays", 200 to false, s.density to s.gemini)
    }

    @Test
    fun `saving and loading keeps profile 1`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(fpsCeiling = 8, bitrateMode = BitrateMode.VBR))
        val back = SettingsStore(prefs).load()
        assertEquals(listOf(4, 400_000, 1), listOf(back.fpsCeiling, back.streamBitrateBps, back.keyframeSec))
        assertEquals("and again at the next start", back, SettingsStore(prefs).load())
    }

    @Test
    fun `with both navigation profiles on, the pessimistic one always wins, whatever was stored`() {
        assertEquals(com.reau.app.dash.NavProfileMode.PESSIMISTA, REauSettings().navProfileMode)
        assertEquals(com.reau.app.dash.NavProfileMode.PESSIMISTA, REauSettings(navProfileMode = com.reau.app.dash.NavProfileMode.OTIMISTA).sanitized().navProfileMode)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putBoolean("conservative_video_20261010", true).putString("nav_profile_mode", "OTIMISTA").apply()
        assertEquals(com.reau.app.dash.NavProfileMode.PESSIMISTA, SettingsStore(prefs).load().navProfileMode)
    }

    @Test
    fun `the profile by maneuver and the profile by speed are always on`() {
        val s = REauSettings()
        assertEquals(true to true, s.maneuverFpsProfile to s.speedFpsProfile)
        val off = REauSettings(maneuverFpsProfile = false, speedFpsProfile = false, maneuverData = false).sanitized()
        assertEquals(true to true, off.maneuverFpsProfile to off.speedFpsProfile)
        assertEquals("the data they live on is fixed on too", true, off.maneuverData)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putBoolean("conservative_video_20261010", true).putBoolean("maneuver_fps_profile", false)
            .putBoolean("speed_fps_profile", false).apply()
        val back = SettingsStore(prefs).load()
        assertEquals(true to true, back.maneuverFpsProfile to back.speedFpsProfile)
    }
}
