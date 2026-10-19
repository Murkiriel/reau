package com.reau.app

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which package is the other edition, and that each edition's manifest lets the package manager show it. */
class OtherEditionTest {
    private val src = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }

    @Test
    fun `each edition points at the other one`() {
        assertEquals("com.reau.app.lite", OtherEdition.packageOf(lite = false))
        assertEquals("com.reau.app", OtherEdition.packageOf(lite = true))
        assertEquals("REau Lite", OtherEdition.nameOf(lite = false))
        assertEquals("REau", OtherEdition.nameOf(lite = true))
    }

    @Test
    fun `this build's own id is the one the other edition points back at`() {
        // Whichever edition this unit test is built as, the other's answer is the id Gradle gave this one.
        assertEquals(BuildConfig.APPLICATION_ID, OtherEdition.packageOf(lite = !Edition.lite))
        assertTrue(OtherEdition.packageOf() != BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `a package manager that cannot be asked says the other edition is not there, never throws`() {
        // The JVM stubs of android.jar return null for the package manager: the call fails, and that is a no.
        assertFalse(OtherEdition.installed(ContextWrapper(null)))
        assertEquals("", OtherEdition.logNote(ContextWrapper(null)))
    }

    @Test
    fun `each edition's manifest queries the other's package, and only that`() {
        // Android 11+ hides packages not listed in queries: without it the answer would always be "not installed".
        val complete = File(src, "completa/AndroidManifest.xml").readText()
        val lite = File(src, "lite/AndroidManifest.xml").readText()
        assertTrue(Regex("""<queries>\s*<package android:name="com\.reau\.app\.lite" />\s*</queries>""").containsMatchIn(complete))
        assertTrue(Regex("""<queries>\s*<package android:name="com\.reau\.app" />\s*</queries>""").containsMatchIn(lite))
        assertFalse("the complete edition does not query itself", "\"com.reau.app\" />" in complete)
        assertFalse("the Lite edition does not query itself", "\"com.reau.app.lite\" />" in lite)
    }

    @Test
    fun `the warning texts exist in both languages and take the other edition's name`() {
        for (folder in listOf("values", "values-pt")) {
            val text = File(src, "main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", Regex("""<string name="warn_other_edition_title">[^<]+</string>""").containsMatchIn(text))
            assertTrue("$folder text takes the name", Regex("""<string name="aa_other_edition_warning">[^<]*%1\${'$'}s[^<]*</string>""").containsMatchIn(text))
        }
    }
}
