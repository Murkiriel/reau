package com.reau.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line of the settings in use is made from a list of names allowed, so what it carries is decided,
 * not whatever the data class holds today.
 */
class SettingsInUseTest {
    private val fields = REauSettings().let { it.fieldsOf(it.toString()) }

    @Test
    fun `every setting is either written or left out on purpose, so a new one has to be decided on`() {
        val undecided = fields.keys - SettingsInUse.LOGGED - SettingsInUse.NOT_LOGGED
        assertEquals("decide in SettingsInUse whether these are written: $undecided", emptySet<String>(), undecided)
        assertEquals("on both lists: ${SettingsInUse.LOGGED intersect SettingsInUse.NOT_LOGGED}", emptySet<String>(), SettingsInUse.LOGGED intersect SettingsInUse.NOT_LOGGED)
        val gone = (SettingsInUse.LOGGED + SettingsInUse.NOT_LOGGED) - fields.keys
        assertEquals("names that are no setting any more: $gone", emptySet<String>(), gone)
    }

    @Test
    fun `the line of the default settings says the ones that change a ride, in the order of the settings`() {
        assertEquals(
            "settings in use: density=${fields.getValue("density")} marginW=0 marginH=0 fit=WHOLE_VISIBLE cropPercent=${fields.getValue("cropPercent")} " +
                "fpsCeiling=${fields.getValue("fpsCeiling")} streamBitrateBps=${fields.getValue("streamBitrateBps")} bitrateMode=CBR " +
                "keyframeSec=${fields.getValue("keyframeSec")} clockSlowPercent=0 " +
                "recordVideo=${com.reau.app.BuildConfig.DEBUG} recordSessions=${com.reau.app.BuildConfig.DEBUG} cardImage=default mapTheme=AUTO maneuverData=true maneuverFpsProfile=true " +
                "speedFpsProfile=true navProfileMode=PESSIMISTA autoJoinWifi=true detailedLog=${com.reau.app.BuildConfig.DEBUG} rideLog=true useLocation=true " +
                "logKeepDays=${if (com.reau.app.BuildConfig.DEBUG) 0 else 30} noDashStopMinutes=20 answerCalls=true phoneTouch=true dashMusic=true dashCaller=true aaNewProtocol=true " +
                "gemini=true clearAaFocus=true dashMask=true " +
                "statsOverlay=false buttonOverlay=false dashPhoneStatus=true dashAlbumArt=true maneuverRing=false dashWeather=true infoRing=false infoRingData=2047 infoRingCarousel=true infoRingSeconds=7 infoRingInPlace=false webView=false webTouch=false webAudio=PHONE " +
                "experimentRtpPacing=false experimentDebugDscp=false experimentAdaptiveBitrate=false experimentWifiBind=false experimentWifiBand=false experimentStartGuard=true experimentEncoderWatch=false experimentFocusGap=false experimentNightMemory=false experimentWeatherAge=false experimentHeatCeiling=false experimentGpsSpeed=false " +
                "experimentRecalcInactive=false experimentColorMatrix=false experimentProtocolSix=false experimentTolerantRx=false",
            SettingsInUse.line(REauSettings()),
        )
    }

    @Test
    fun `the line no longer says a slower Android Auto, which is gone`() {
        assertEquals(false, "aaSlowPace" in SettingsInUse.line(REauSettings()))
    }

    @Test
    fun `a setting the rider turned on shows`() {
        val line = SettingsInUse.line(REauSettings(detailedLog = true, statsOverlay = true))
        for (part in listOf("detailedLog=true", "statsOverlay=true")) {
            assertTrue("$part missing from: $line", part in line)
        }
    }

    @Test
    fun `the picture the rider picked is not said, only that it is not the built-in one`() {
        val picked = SettingsInUse.line(REauSettings(cardImage = "content://com.android.providers.media/secret-folder/my photo.jpg"))
        assertTrue(picked, " cardImage=picked " in "$picked ")
        assertFalse(picked, "secret" in picked || "photo" in picked || "content:" in picked)
        assertTrue(SettingsInUse.line(REauSettings(cardImage = null)).contains("cardImage=none"))
    }

    @Test
    fun `nothing but names, numbers and truths goes to the line, and what is left out stays out`() {
        val odd = REauSettings(joystick = "free text, with a path /storage/emulated/0/x and a name Fulano")
        val line = SettingsInUse.line(odd)
        assertFalse(line, "Fulano" in line || "/storage" in line || "joystick" in line || "free text" in line)
        val words = line.removePrefix("settings in use: ").split(" ")
        for (word in words) {
            assertTrue("a pair of plain words: '$word'", word.matches(Regex("""[A-Za-z]+=[A-Za-z0-9_.-]+""")))
        }
        for (left in SettingsInUse.NOT_LOGGED) assertFalse("$left written", words.any { it.startsWith("$left=") })
    }
}
