package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher shortcut and the Quick Settings tile: they switch a stopped projection on, and nothing else. */
class QuickStartTest {
    private val src = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }
    private val stopped = Projection()

    @Test
    fun `only the quick start action on a stopped projection switches it on`() {
        assertTrue(QuickStart.switchesOn(QuickStart.ACTION, stopped, termsAccepted = true, unlocked = true))
        Phase.entries.filter { it != Phase.STOPPED }.forEach {
            assertEquals("$it", false, QuickStart.switchesOn(QuickStart.ACTION, Projection(phase = it), termsAccepted = true, unlocked = true))
        }
        assertEquals(false, QuickStart.switchesOn("android.intent.action.MAIN", stopped, termsAccepted = true, unlocked = true))
        assertEquals(false, QuickStart.switchesOn(null, stopped, termsAccepted = true, unlocked = true))
    }

    @Test
    fun `a projection still being stopped is not switched on again`() {
        assertEquals(false, QuickStart.switchesOn(QuickStart.ACTION, Projection(stopping = true), termsAccepted = true, unlocked = true))
    }

    @Test
    fun `before the terms of use are accepted the shortcut switches nothing on`() {
        assertEquals(false, QuickStart.switchesOn(QuickStart.ACTION, stopped, termsAccepted = false, unlocked = true))
    }

    @Test
    fun `with the phone locked nothing is switched on, whatever else is right`() {
        assertEquals(false, QuickStart.switchesOn(QuickStart.ACTION, stopped, termsAccepted = true, unlocked = false))
        assertEquals(false, QuickStart.switchesOn(QuickStart.ACTION, stopped, termsAccepted = false, unlocked = false))
    }

    @Test
    fun `with the phone unlocked the shortcut switches a stopped projection on, as before`() {
        assertTrue(QuickStart.switchesOn(QuickStart.ACTION, stopped, termsAccepted = true, unlocked = true))
        assertEquals("a running projection is left alone", false,
            QuickStart.switchesOn(QuickStart.ACTION, Projection(phase = Phase.RUNNING), termsAccepted = true, unlocked = true))
    }

    @Test
    fun `the activity asks the keyguard, takes a missing keyguard as locked and does not keep the action for later`() {
        val main = File(src, "main/java/com/reau/app/MainActivity.kt").readText()
        assertTrue(main.contains("(getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == false"))
        assertTrue(main.contains("QuickStart.switchesOn(intent?.action, projection, termsAcceptedNow(), unlocked)"))
        val locked = main.substringAfter("if (intent?.action == com.reau.app.service.QuickStart.ACTION && !unlocked) {").substringBefore("return")
        assertTrue("the action is cleared when locked", "intent.action = null" in locked)
    }

    @Test
    fun `the tile and the shortcut are still there for a locked phone to open the app`() {
        val manifest = File(src, "main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".service.QuickStartTile\""))
        assertTrue(File(src, "main/res/xml/shortcuts.xml").readText().contains(QuickStart.ACTION))
    }

    @Test
    fun `the shortcut opens the app's package, with the action the activity reads`() {
        val xml = File(src, "main/res/xml/shortcuts.xml").readText()
        assertTrue("action", "android:action=\"${QuickStart.ACTION}\"" in xml)
        assertTrue("package", "android:targetPackage=\"com.reau.app\"" in xml)
        assertTrue("class", "android:targetClass=\"com.reau.app.MainActivity\"" in xml)
    }

    @Test
    fun `the manifest registers the shortcuts and a tile only the system can bind`() {
        val manifest = File(src, "main/AndroidManifest.xml").readText()
        assertTrue(Regex("""android:name="android\.app\.shortcuts"\s+android:resource="@xml/shortcuts"""").containsMatchIn(manifest))
        val tile = Regex("""<service\s+android:name="\.service\.QuickStartTile"[^>]*>""").find(manifest)?.value ?: error("no tile in the manifest")
        assertTrue(tile, "android:permission=\"android.permission.BIND_QUICK_SETTINGS_TILE\"" in tile)
    }
}
