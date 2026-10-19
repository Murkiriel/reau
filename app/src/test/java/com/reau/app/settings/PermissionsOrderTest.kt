package com.reau.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions page: inside each section the permissions go by how much REau's work depends on
 * them (what stops the projection first, what only serves an optional switch last), and the texts are short.
 */
class PermissionsOrderTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
    private val strings = File("src/main/res/values-pt/strings.xml").readText()

    private fun rowsOf(kind: AppPermissions.Kind): List<String> =
        AppPermissions.rows(declared.reversed(), { true }, accessibilityOn = true, batteryExempt = true)
            .filter { it.explained.kind == kind }.map { it.explained.permission.removePrefix("android.permission.") }

    @Test
    fun `the special accesses put accessibility, which the Android Auto server needs, before the battery`() {
        assertEquals(listOf(AppPermissions.ACCESSIBILITY, AppPermissions.BATTERY), rowsOf(AppPermissions.Kind.SPECIAL))
    }

    @Test
    fun `the ones the rider grants go from what the connection needs to what only serves a switch`() {
        assertEquals(
            listOf(
                "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", // the full Wi-Fi name of the bike, or no automatic connection
                "POST_NOTIFICATIONS", // the projection's own notification and its off button
                "ANSWER_PHONE_CALLS", "READ_PHONE_STATE", // calls by the joystick (a switch)
                "RECORD_AUDIO", // Gemini by the joystick (a switch)
            ),
            rowsOf(AppPermissions.Kind.ASKED),
        )
    }

    @Test
    fun `the ones Android grants at install go from what stops everything to what only serves Gemini`() {
        assertEquals(
            listOf(
                "INTERNET", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", // no socket or no service without them
                "CHANGE_NETWORK_STATE", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", // the link to the dash
                "WAKE_LOCK", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", // staying alive with the screen off
                "FOREGROUND_SERVICE_MICROPHONE", "MODIFY_AUDIO_SETTINGS", // only for Gemini
            ),
            rowsOf(AppPermissions.Kind.INSTALL),
        )
    }

    private fun text(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("sem o texto $name")

    @Test
    fun `the page texts are short`() {
        val why = Regex("""<string name="(perm_\w+_why)">""").findAll(strings).map { it.groupValues[1] }.toList()
        assertTrue("the why texts were found: $why", why.size >= 18)
        for (name in why) {
            val limit = if (name == "perm_accessibility_why") 320 else 280
            assertTrue("$name tem ${text(name).length} letras", text(name).length <= limit)
        }
        assertTrue("a introdução tem ${text("permissions_intro").length} letras", text("permissions_intro").length <= 260)
        assertTrue("a nota tem ${text("permissions_ask_all_note").length} letras", text("permissions_ask_all_note").length <= 220)
    }
}
