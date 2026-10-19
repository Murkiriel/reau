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
                "Kotlin and kotlinx-coroutines", "JetBrains Mono", "Open-Meteo", "JMuxer",
            ),
            libs.map { it.name },
        )
    }

    @Test
    fun `every project REau uses is credited with its licence`() {
        val expected = mapOf(
            "OpenCfMoto" to "AGPL-3.0", "headunit-revived" to "AGPL-3.0", "open-headunit" to "AGPL-3.0", "headunit" to "AGPL-3.0",
            "MOTO-HUB" to "AGPL-3.0", "NorthStar" to "Apache-2.0", "better-dash" to "Apache-2.0",
            "recon-dash" to "AGPL-3.0", "open-dash" to "Apache-2.0",
            "EstrelaAustral" to "Apache-2.0", "AndroidX and Jetpack Compose" to "Apache-2.0", "protobuf-java" to "BSD-3-Clause",
            "Conscrypt" to "Apache-2.0",
            // The language and the coroutines inside the APK, and the weather's data (CC BY asks for the credit).
            "Kotlin and kotlinx-coroutines" to "Apache-2.0", "Open-Meteo" to "CC-BY-4.0",
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
            assertTrue(e.name, e.url.startsWith("https://"))
        }
        assertTrue("every line names a holder", all.none { it.holder.isBlank() })
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
}
