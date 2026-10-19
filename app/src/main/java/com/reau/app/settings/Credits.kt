package com.reau.app.settings

import androidx.annotation.StringRes
import com.reau.app.R

/**
 * The credits page: one project per line, the name, "© holder · licence", and its role. The list holds every project
 * whose code or published facts REau uses, directly or through another project in it, grouped by the side of the app
 * it serves. Names and holders are as each project states them in its own LICENSE or NOTICE. What each project gave to REau ([Entry.role]) is a text of the resources, in both languages; the few names and
 * holders with a word of the language in them have a resource too ([Entry.nameText], [Entry.holderText]), and the
 * [Entry.name] and [Entry.holder] given are then the language-free keys (the tag of the line, the order of the list).
 */
object Credits {
    enum class Side { ANDROID_AUTO, DASH, LIBRARIES }

    data class Entry(
        val name: String,
        val holder: String,
        val licence: String,
        @StringRes val role: Int,
        val url: String,
        /** The name as the rider reads it, when the name has a word of the language in it; 0 shows [name]. */
        @StringRes val nameText: Int = 0,
        /** The holder as the rider reads it, when it has a word of the language in it; 0 shows [holder]. */
        @StringRes val holderText: Int = 0,
    )

    data class Group(val side: Side, val entries: List<Entry>)

    private const val AGPL = "AGPL-3.0"
    private const val APACHE = "Apache-2.0"
    private const val OFL = "OFL-1.1"
    private const val MIT = "MIT"

    /**
     * Where REau's own source code is published. REau is under the AGPL-3.0, whose section 13 asks that a user of the
     * app can get its source: the credits page shows this address and copies it on a tap.
     */
    const val SOURCE_URL = "https://github.com/Murkiriel/reau"

    val groups: List<Group> = listOf(
        Group(
            Side.ANDROID_AUTO,
            listOf(
                Entry("OpenCfMoto", "© 2026 Alexandru", AGPL,
                    R.string.credit_role_opencfmoto, "https://github.com/zanderp/open-cfmoto", holderText = R.string.credit_holder_opencfmoto),
                Entry("headunit-revived", "© andreknieriem", AGPL,
                    R.string.credit_role_headunit_revived, "https://github.com/andreknieriem/headunit-revived",
                    holderText = R.string.credit_holder_headunit_revived),
                Entry("open-headunit", "© andreknieriem", AGPL,
                    R.string.credit_role_open_headunit, "https://github.com/andreknieriem/open-headunit"),
                Entry("headunit", "© 2011-2015 Michael A. Reid", AGPL,
                    R.string.credit_role_headunit, "https://github.com/mikereidis/headunit"),
                Entry("MOTO-HUB", "© 2026 Vincenzo Buonomano", AGPL,
                    R.string.credit_role_moto_hub, "https://github.com/vincenzobpt/MOTO-HUB", holderText = R.string.credit_holder_moto_hub),
            ),
        ),
        Group(
            Side.DASH,
            listOf(
                Entry("NorthStar", "© 2026 Aditya Dasika", APACHE,
                    R.string.credit_role_northstar,
                    "https://github.com/adityadasika21/NorthStar"),
                Entry("EstrelaAustral", "© 2026 Murkiriel", APACHE,
                    R.string.credit_role_estrelaaustral, "https://github.com/Murkiriel/EstrelaAustral"),
                Entry("better-dash", "© norbertFeron", APACHE,
                    R.string.credit_role_better_dash, "https://github.com/norbertFeron/better-dash"),
                Entry("recon-dash", "© MinotaurG", AGPL,
                    R.string.credit_role_recon_dash, "https://github.com/MinotaurG/recon-dash"),
                Entry("open-dash", "© 2026 Subtle Sayak", APACHE,
                    R.string.credit_role_open_dash, "https://github.com/subtlesayak/open-dash", holderText = R.string.credit_holder_open_dash),
            ),
        ),
        Group(
            Side.LIBRARIES,
            // By maker, A to Z (the leading year and "The" do not count), each maker's lines together and by name; the
            // fonts and the icons are the ones inside the APK (NOTICE, licenses/). A year shows only where the project
            // states one: the fonts' in their own name tables, protobuf's in its LICENSE.
            listOf(
                Entry(
                    "Fraunces", "© 2020 The Fraunces Project Authors (Undercase Type)", OFL, R.string.credit_role_fraunces,
                    "https://github.com/undercasetype/Fraunces",
                ),
                Entry("AndroidX and Jetpack Compose", "© Google", APACHE, R.string.credit_role_androidx, "https://developer.android.com/jetpack/androidx",
                    nameText = R.string.credit_name_androidx),
                Entry("Conscrypt", "© Google", APACHE, R.string.credit_role_conscrypt, "https://github.com/google/conscrypt"),
                Entry("Material Symbols", "© Google", APACHE, R.string.credit_role_material_symbols, "https://github.com/google/material-design-icons"),
                Entry("protobuf-java", "© 2008 Google", "BSD-3-Clause", R.string.credit_role_protobuf, "https://github.com/protocolbuffers/protobuf"),
                Entry(
                    "Hanken Grotesk", "© 2021 The Hanken Grotesk Project Authors", OFL, R.string.credit_role_hanken,
                    "https://github.com/marcologous/hanken-grotesk",
                ),
                Entry(
                    "Kotlin and kotlinx-coroutines", "© JetBrains", APACHE, R.string.credit_role_kotlin,
                    "https://github.com/JetBrains/kotlin", nameText = R.string.credit_name_kotlin, holderText = R.string.credit_holder_kotlin,
                ),
                Entry(
                    "JetBrains Mono", "© 2020 The JetBrains Mono Project Authors", OFL, R.string.credit_role_jetbrains_mono,
                    "https://github.com/JetBrains/JetBrainsMono",
                ),
                // CC BY 4.0 asks for the credit; the free API asks for "Weather data by Open-Meteo.com" with a link.
                Entry("Open-Meteo", "© Open-Meteo.com", "CC-BY-4.0", R.string.credit_role_open_meteo, "https://open-meteo.com"),
                // MIT asks for the notice to travel with the code: the licence text is in assets/web/jmuxer.LICENSE, inside the APK.
                Entry("JMuxer", "© 2018 Samir Das", MIT, R.string.credit_role_jmuxer, "https://github.com/samirkumardas/jmuxer"),
            ),
        ),
    )
}
