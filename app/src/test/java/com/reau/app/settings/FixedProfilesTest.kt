package com.reau.app.settings

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun root(): java.io.File = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the profiles' bitrates are written once, in DashConfig, and not as a number in other code`() {
        val main = java.io.File(root(), "app/src/main/java/com/reau/app")
        val literal = Regex("""\b(?:400_?000|200_?000)\b""")
        // Relative to the root, so a project under a folder called "proto" is still scanned.
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "DashConfig.kt" }
            .filter { !("/" + it.relativeTo(main).path).contains("/proto/") }.toList()
        assertTrue("the guard found only ${files.size} files", files.size > 200)
        val offenders = files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.trim()
                !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") && literal.containsMatchIn(code)
            }.map { (n, _) -> "${file.name}:${n + 1}" }
        }
        assertEquals("use DashConfig.PROFILE_*_BPS: $offenders", emptyList<String>(), offenders)
    }

    /**
     * Decided on 28/10/2026: the recordings exist in a release too, off by default, and the rider switches them on in Settings > Debug. What is
     * stored is kept as it is in both builds (it was forced off in a release); only the two texts over the dash's video stay a debug build's.
     */
    @Test
    fun `the recordings are kept as stored in a release as in a debug build`() {
        val on = REauSettings(recordVideo = true, recordSessions = true)
        for (debugBuild in listOf(true, false)) {
            assertTrue("recordVideo stays on (debugBuild=$debugBuild)", on.sanitized(debugBuild = debugBuild).recordVideo)
            assertTrue("recordSessions stays on (debugBuild=$debugBuild)", on.sanitized(debugBuild = debugBuild).recordSessions)
        }
        val off = REauSettings(recordVideo = false, recordSessions = false)
        assertFalse(off.sanitized(debugBuild = false).recordVideo)
        assertFalse(off.sanitized(debugBuild = false).recordSessions)
        val one = REauSettings(recordVideo = true, recordSessions = false).sanitized(debugBuild = false)
        assertEquals(true to false, one.recordVideo to one.recordSessions)
    }

    @Test
    fun `the recordings are off by default in a release and on in a debug build, as the build type says`() {
        val source = java.io.File(root(), "app/src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        for (name in listOf("recordVideo", "recordSessions")) {
            assertTrue("$name's default is the build type (off in a release)", Regex("""val $name: Boolean = com\.reau\.app\.BuildConfig\.DEBUG""").containsMatchIn(source))
        }
    }

    @Test
    fun `the detailed log is no debug option, a release keeps it on when the rider switches it on, and it starts off there`() {
        assertTrue(REauSettings(detailedLog = true).sanitized(debugBuild = false).detailedLog)
        assertTrue("the ride log stays on in a release", REauSettings(rideLog = true).sanitized(debugBuild = false).rideLog)
    }

    @Test
    fun `the debug build starts with its choices on, and they are written as the build type so a release starts with them off`() {
        val s = REauSettings()
        assertEquals("the unit tests run the debug build", com.reau.app.BuildConfig.DEBUG, true)
        assertEquals(listOf(true, true, true, true), listOf(s.rideLog, s.recordVideo, s.recordSessions, s.detailedLog))
        val source = java.io.File(root(), "app/src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        for (name in listOf("recordVideo", "recordSessions", "detailedLog")) {
            assertTrue("$name's default is the build type", Regex("""val $name: Boolean = com\.reau\.app\.BuildConfig\.DEBUG""").containsMatchIn(source))
        }
        assertTrue("the ride log is on in a release too", Regex("""val rideLog: Boolean = true""").containsMatchIn(source))
    }

    @Test
    fun `the protocol request is sent once, and the debug options that went are gone`() {
        assertEquals(1, REauSettings().aaConfig.versionRequests)
        val names = REauSettings::class.java.declaredFields.map { it.name }
        assertEquals(emptyList<String>(), names.filter { it in listOf("aaSingleVersionRequest", "diagLinkOnly", "fasterMenu", "diagNoDscp") })
    }

    @Test
    fun `the faster picture is always on, in a debug build and in a release, with no switch`() {
        assertEquals(com.reau.app.dash.DashConfig.LIGHT_SCREEN_FPS, REauSettings.lightScreenFps())
    }
}
