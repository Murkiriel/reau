package com.reau.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credits: one project per line, name, "© holder · licence", its role. Every project whose code or facts REau uses
 * is there, grouped by the side of the app it serves, with the licence its terms ask to be named.
 */
class CreditsTest {
    private val all = Credits.groups.flatMap { it.entries }

    /** The one line with no address to copy: HeadQLink's repository is not known here (the NOTICE says so). */
    private val NO_ADDRESS = "EstrelaAustral"

    @Test
    fun `the three groups, in order`() {
        assertEquals(listOf(Credits.Side.ANDROID_AUTO, Credits.Side.DASH, Credits.Side.LIBRARIES), Credits.groups.map { it.side })
    }

    @Test
    fun `in the dash group the starting point comes second, right after the first dash project`() {
        val dash = Credits.groups.single { it.side == Credits.Side.DASH }.entries.map { it.name }
        assertEquals(listOf("NorthStar", "EstrelaAustral"), dash.take(2))
    }

    @Test
    fun `libraries and fonts go by their maker, A to Z, with each maker's lines together`() {
        val libs = Credits.groups.single { it.side == Credits.Side.LIBRARIES }.entries
        fun maker(e: Credits.Entry) = e.holder.removePrefix("© ").replace(Regex("^\\d{4}(-\\d{4})? "), "").removePrefix("The ").lowercase()
        assertEquals(libs.sortedWith(compareBy({ maker(it) }, { it.name.lowercase() })), libs)
        assertEquals(
            listOf(
                "Fraunces", "AndroidX and Jetpack Compose", "Conscrypt", "Material Symbols", "protobuf-java", "Hanken Grotesk",
                "Kotlin and kotlinx-coroutines", "JetBrains Mono", "NOAA solar position equations", "Open-Meteo", "JMuxer",
            ),
            libs.map { it.name },
        )
    }

    @Test
    fun `every project REau uses is credited with its licence`() {
        val expected = mapOf(
            "OpenCfMoto" to "AGPL-3.0", "headunit-revived" to "AGPL-3.0", "open-headunit" to "AGPL-3.0", "headunit" to "AGPL-3.0",
            "MOTO-HUB" to "AGPL-3.0", "HeadQLink" to "AGPL-3.0", "aa-proxy-rs" to "GPL-2.0", "NorthStar" to "Apache-2.0", "better-dash" to "Apache-2.0",
            "recon-dash" to "AGPL-3.0", "open-dash" to "Apache-2.0",
            "EstrelaAustral" to "Apache-2.0", "AndroidX and Jetpack Compose" to "Apache-2.0", "protobuf-java" to "BSD-3-Clause",
            "Conscrypt" to "Apache-2.0",
            // The language and the coroutines inside the APK, and the weather's data (CC BY asks for the credit).
            "Kotlin and kotlinx-coroutines" to "Apache-2.0", "Open-Meteo" to "CC-BY-4.0",
            // The sun's equations behind the day/night theme: NOAA publishes them for anyone's use.
            "NOAA solar position equations" to "Public domain",
            // The player of the page the web server gives the browsers, inside the APK's assets.
            "JMuxer" to "MIT",
            // The fonts and the icons inside the APK.
            "Fraunces" to "OFL-1.1", "Hanken Grotesk" to "OFL-1.1", "JetBrains Mono" to "OFL-1.1", "Material Symbols" to "Apache-2.0",
        )
        assertEquals(expected.keys, all.map { it.name }.toSet())
        for (e in all) assertEquals(e.name, expected[e.name], e.licence)
    }

    @Test
    fun `each line has a holder, a role and an address to copy`() {
        for (e in all) {
            assertTrue(e.name, e.role != 0)
            // EstrelaAustral is a private repository: its line has no address to copy (the NOTICE says so); every other has one.
            assertTrue(e.name, e.url.startsWith("https://") || (e.name == NO_ADDRESS && e.url.isEmpty()))
        }
        assertTrue("every line names a holder", all.none { it.holder.isBlank() })
    }

    /** Checked with `gh` on 30/10/2026: these projects moved, and the old address only redirects. The new one is the one given. */
    @Test
    fun `the addresses given are the projects' current ones, not the ones that redirect`() {
        val urls = all.associate { it.name to it.url }
        assertEquals("https://github.com/aa-proxy/aa-proxy-rs", urls["aa-proxy-rs"])
        assertEquals("https://github.com/OpenMotoDash/better-dash", urls["better-dash"])
        assertEquals("https://github.com/webstream-labs/jmuxer", urls["JMuxer"])
        assertEquals("https://github.com/ryazrm/headqlink_devel", urls["HeadQLink"])
        val notice = java.io.File("../NOTICE").readText()
        assertTrue("the NOTICE says EstrelaAustral's repository is private", "a private repository" in notice.substringAfter("EstrelaAustral (Apache License 2.0)"))
    }

    @Test
    fun `the starting point is credited for what REau took from it`() {
        val role = all.single { it.name == "EstrelaAustral" }.role
        assertEquals("the starting-point text, in both languages' resources", com.reau.app.R.string.credit_role_estrelaaustral, role)
    }

    @Test
    fun `REau's own source address is the one the Credits screen gives`() {
        assertEquals("https://github.com/Murkiriel/reau", Credits.SOURCE_URL)
    }

    @Test
    fun `the weather's data is credited as Open-Meteo asks, with its address`() {
        val e = all.single { it.name == "Open-Meteo" }
        assertTrue(e.holder, e.holder.contains("Open-Meteo.com"))
        assertEquals("https://open-meteo.com", e.url)
    }

    @Test
    fun `the fonts and protobuf carry the year their own font tables and licence files state`() {
        val years = mapOf("Fraunces" to "2020", "Hanken Grotesk" to "2021", "JetBrains Mono" to "2020", "protobuf-java" to "2008", "JMuxer" to "2018")
        for ((name, year) in years) assertTrue(name, all.single { it.name == name }.holder.startsWith("© $year "))
    }

    /** Audit of 20/10/2026 (second pass): the credits screen linked projects (the fonts', open-headunit's holder) that the NOTICE did not. */
    @Test
    fun `the NOTICE carries the address of every project the Credits screen links`() {
        val notice = java.io.File("../NOTICE").readText()
        // The AndroidX line links the library family's page, the NOTICE the Jetpack page it belongs to.
        val shorter = mapOf("AndroidX and Jetpack Compose" to "https://developer.android.com/jetpack")
        val missing = all.filter { it.url.isNotEmpty() }.filter { e -> (shorter[e.name] ?: e.url) !in notice }.map { "${it.name} ${it.url}" }
        assertEquals("addresses the Credits screen has and the NOTICE has not", emptyList<String>(), missing)
    }

    @Test
    fun `the NOTICE names the holder the Credits screen gives open-headunit`() {
        val notice = java.io.File("../NOTICE").readText()
        val block = notice.substringAfter("open-headunit (GNU Affero").substringBefore("headunit-revived (GNU Affero")
        assertTrue(all.single { it.name == "open-headunit" }.holder, "Copyright andreknieriem" in block)
    }

    @Test
    fun `the NOTICE lists the weather among the parts copied or adapted from EstrelaAustral, with files that exist`() {
        val notice = java.io.File("../NOTICE").readText()
        val block = notice.substringAfter("EstrelaAustral (Apache License 2.0)").substringBefore("OpenCfMoto (GNU Affero")
        assertTrue("copied or adapted", "copied or adapted from EstrelaAustral" in block)
        assertTrue("the weather", "the weather (weather/ and its screen, ui/WeatherScreen.kt)" in block.replace(Regex("\\s+"), " "))
        assertTrue(java.io.File("src/main/java/com/reau/app/weather").isDirectory)
        assertTrue(java.io.File("src/main/java/com/reau/app/ui/WeatherScreen.kt").isFile)
    }

    @Test
    fun `NOAA is credited as public domain, with the address its NOTICE block gives`() {
        val e = all.single { it.name == "NOAA solar position equations" }
        assertEquals("Public domain", e.licence)
        val notice = java.io.File("../NOTICE").readText()
        val block = notice.substringAfter("NOAA solar position equations (public domain)").substringBefore("Open-Meteo (data under")
        assertTrue(e.url, e.url in block)
        assertTrue("data/SolarClock.kt" in block)
        assertTrue(java.io.File("src/main/java/com/reau/app/data/SolarClock.kt").isFile)
    }

    @Test
    fun `the public domain licence is said in the rider's language, and only that one`() {
        val en = java.io.File("src/main/res/values/strings_extra.xml").readText()
        val pt = java.io.File("src/main/res/values-pt/strings_extra.xml").readText()
        assertTrue(en, "name=\"credit_licence_public_domain\">Public domain<" in en)
        assertTrue(pt, "name=\"credit_licence_public_domain\">Domínio público<" in pt)
        assertEquals(listOf("NOAA solar position equations"), Credits.groups.flatMap { it.entries }.filter { it.licence == Credits.PUBLIC_DOMAIN }.map { it.name })
        assertTrue(java.io.File("src/main/java/com/reau/app/ui/CreditsScreen.kt").readText().contains("Credits.PUBLIC_DOMAIN"))
    }
}
