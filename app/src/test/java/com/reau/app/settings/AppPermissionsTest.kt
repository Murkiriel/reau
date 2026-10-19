package com.reau.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions page: a button listing every permission and why REau uses it.
 * Every permission the manifest asks for has its own explanation; one the build adds that REau does not know (a
 * library's) still shows, with the library text, after the known ones; the special accesses (accessibility, battery)
 * come first, since they are the ones the rider switches by hand.
 */
class AppPermissionsTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()

    @Test
    fun `every permission of the manifest has its own explanation`() {
        assertTrue("the manifest was read: $declared", declared.size >= 15)
        val unexplained = declared.filter { AppPermissions.explain(it).kind == AppPermissions.Kind.LIBRARY }
        assertEquals("no permission falls to the library text", emptyList<String>(), unexplained)
    }

    @Test
    fun `nearby devices is gone, since it never opened the Wi-Fi list`() {
        val nearby = "android.permission.NEARBY_WIFI_DEVICES"
        assertFalse("the manifest no longer asks for it", nearby in declared)
        assertEquals("the page no longer explains it", AppPermissions.Kind.LIBRARY, AppPermissions.explain(nearby).kind)
    }

    @Test
    fun `the internal receiver permission AndroidX adds is told as the library's`() {
        assertEquals(AppPermissions.Kind.LIBRARY, AppPermissions.explain("com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION").kind)
        assertEquals(AppPermissions.Kind.LIBRARY, AppPermissions.explain("android.permission.SOMETHING_NEW").kind)
    }

    @Test
    fun `the list puts the special accesses first, then the known ones in the catalog's order, then the rest`() {
        val requested = listOf(
            "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET",
        )
        val rows = AppPermissions.rows(
            requested = requested,
            granted = { it == "android.permission.INTERNET" },
            accessibilityOn = false,
            batteryExempt = true,
        )
        assertEquals(
            listOf(
                AppPermissions.ACCESSIBILITY, AppPermissions.BATTERY,
                // the ones that depend on the rider before the ones Android grants at install
                "android.permission.RECORD_AUDIO", "android.permission.INTERNET",
                "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            rows.map { it.explained.permission },
        )
        assertFalse("accessibility off", rows[0].granted)
        assertTrue("battery exempt", rows[1].granted)
        assertFalse("microphone not granted", rows[2].granted)
        assertTrue("internet granted", rows[3].granted)
    }

    @Test
    fun `each permission is told once, even when two names share an explanation`() {
        val rows = AppPermissions.rows(
            requested = listOf(
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.INTERNET", "android.permission.INTERNET",
            ),
            granted = { false },
            accessibilityOn = true,
            batteryExempt = false,
        )
        assertEquals(5, rows.size)
        assertEquals(rows.size, rows.map { it.explained.permission }.toSet().size)
    }

    @Test
    fun `accessibility switched on that Android does not run is told as stuck, and only then`() {
        fun first(on: Boolean, stuck: Boolean) =
            AppPermissions.rows(emptyList(), { false }, accessibilityOn = on, batteryExempt = false, accessibilityStuck = stuck).first()
        assertTrue("on in the settings, not running", first(on = false, stuck = true).stuck)
        assertFalse("running", first(on = true, stuck = true).stuck)
        assertFalse("off", first(on = false, stuck = false).stuck)
        assertFalse("battery is never stuck", AppPermissions.rows(emptyList(), { false }, false, false, accessibilityStuck = true)[1].stuck)
    }

    @Test
    fun `the ones Android grants at install are told apart from the ones the rider grants`() {
        assertEquals(AppPermissions.Kind.INSTALL, AppPermissions.explain("android.permission.INTERNET").kind)
        assertEquals(AppPermissions.Kind.ASKED, AppPermissions.explain("android.permission.RECORD_AUDIO").kind)
        assertEquals(AppPermissions.Kind.ASKED, AppPermissions.explain("android.permission.POST_NOTIFICATIONS").kind)
        assertEquals(AppPermissions.Kind.SPECIAL, AppPermissions.explain(AppPermissions.ACCESSIBILITY).kind)
    }

    // On a clean install, Android 13+ blocked the accessibility switch of an APK installed from a
    // file ("Configuração restrita") until "Permitir configurações restritas" in REau's app info.

    @Test
    fun `restricted settings are said on Android 13 and newer for an app not from the Play Store`() {
        assertTrue(AppPermissions.restrictedSettings(sdk = 34, installer = "com.google.android.packageinstaller"))
        assertTrue(AppPermissions.restrictedSettings(sdk = 33, installer = null))
        assertFalse("from the Play Store", AppPermissions.restrictedSettings(sdk = 34, installer = "com.android.vending"))
        assertFalse("before Android 13", AppPermissions.restrictedSettings(sdk = 32, installer = null))
    }

    @Test
    fun `the accessibility row is restricted only while it is off`() {
        fun row(on: Boolean) = AppPermissions.rows(emptyList(), { false }, accessibilityOn = on, batteryExempt = false, accessibilityRestricted = true).first()
        assertTrue(row(on = false).restricted)
        assertFalse(row(on = true).restricted)
        assertFalse("battery is never restricted", AppPermissions.rows(emptyList(), { false }, false, false, accessibilityRestricted = true)[1].restricted)
    }
}
