package com.reau.app

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old "lite" app (id com.reau.app.lite): the one package this app looks for, and what lets the package manager show it. */
class OtherEditionTest {
    private val src = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }

    @Test
    fun `the old app looked for is the one with the id of this app and the suffix lite, never this app itself`() {
        assertEquals("com.reau.app.lite", OtherEdition.LITE_ID)
        assertEquals(BuildConfig.APPLICATION_ID + ".lite", OtherEdition.LITE_ID)
        assertTrue(OtherEdition.LITE_ID != BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `a package manager that cannot be asked says the old app is not there, never throws`() {
        // The JVM stubs of android.jar return null for the package manager: the call fails, and that is a no.
        assertFalse(OtherEdition.installed(ContextWrapper(null)))
        assertEquals("", OtherEdition.logNote(ContextWrapper(null)))
    }

    @Test
    fun `the manifest queries the old app's package, and not this app's own`() {
        // Android 11+ hides packages not listed in queries: without it the answer would always be "not installed".
        val manifest = File(src, "main/AndroidManifest.xml").readText()
        assertTrue(Regex("""<package android:name="com\.reau\.app\.lite" />""").containsMatchIn(manifest))
        assertFalse("the app does not query itself", "<package android:name=\"com.reau.app\" />" in manifest)
    }

    @Test
    fun `no product flavor and no source folder of the old editions are left in the build`() {
        for (old in listOf("completa", "lite", "androidTestCompleta")) assertFalse("src/$old is still there", File(src, old).exists())
        val build = File(src, "../build.gradle.kts").readText()
        assertFalse("productFlavors", "productFlavors" in build)
        assertFalse("flavorDimensions", Regex("""flavorDimensions\s*\+=""").containsMatchIn(build))
        assertFalse("BuildConfig.LITE", "buildConfigField(\"boolean\", \"LITE\"" in build)
    }

    @Test
    fun `the warning and the note about the old app exist in the three languages, without the word lite`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val text = File(src, "main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", Regex("""<string name="warn_other_edition_title">[^<]+</string>""").containsMatchIn(text))
            for (name in listOf("aa_other_edition_warning", "old_app_note")) {
                val body = Regex("""<string name="$name">([^<]+)</string>""").find(text)?.groupValues?.get(1) ?: error("$folder: no $name")
                assertFalse("$folder $name must not take a name: it has no %1\$s", "%1\$s" in body)
                assertFalse("$folder $name must not say lite", body.contains("lite", ignoreCase = true))
            }
        }
    }
}
