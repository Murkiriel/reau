package com.reau.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity the phone knows the app by: one app, one package (com.reau.app; since 0.3.0 there is no second edition), and the
 * version is semantic (0.y.z);
 * debug builds add the build's own timestamp (-dev+yy.MM.dd-HH.mm), which the ride log prints so a log can be matched to
 * the APK that wrote it.
 */
class AppIdentityTest {

    @Test
    fun `the app is its own package`() {
        assertEquals("com.reau.app", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `the version name is semantic, with the build's timestamp on debug builds`() {
        val semantic = Regex("""\d+\.\d+\.\d+(-dev\+\d{2}\.\d{2}\.\d{2}-\d{2}\.\d{2})?""")
        assertTrue(BuildConfig.VERSION_NAME, semantic.matches(BuildConfig.VERSION_NAME))
    }

    @Test
    fun `this is the 0 3 line of releases`() {
        assertTrue(BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME.startsWith("0.3."))
    }

    @Test
    fun `a build made later has a higher version code`() {
        // Minutes since the Unix epoch: any build of 2026 or later is above 29 million.
        assertTrue(BuildConfig.VERSION_CODE.toString(), BuildConfig.VERSION_CODE > 29_000_000)
    }
}
