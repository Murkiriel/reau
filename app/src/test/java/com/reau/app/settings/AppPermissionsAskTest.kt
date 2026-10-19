package com.reau.app.settings

import com.reau.app.settings.AppPermissions.Ask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On the permissions page, a button to ask for what is not granted. Each row knows how:
 * a runtime permission by Android's dialog (the two phone ones together, the two location ones together, and location
 * only when the rider allowed REau to use it), the accessibility by its settings page, the battery by Android's own
 * request; the ones granted at install, a library's, or one already granted ask nothing.
 */
class AppPermissionsAskTest {
    private val sdk = 34

    private fun row(permission: String, granted: Boolean = false) = AppPermissions.Row(AppPermissions.explain(permission), granted)

    @Test
    fun `runtime permissions are asked by Android's dialog, the related ones together`() {
        assertEquals(Ask.Runtime(listOf("android.permission.RECORD_AUDIO")), AppPermissions.ask(row("android.permission.RECORD_AUDIO"), sdk, locationAllowed = false))
        val phone = Ask.Runtime(listOf("android.permission.ANSWER_PHONE_CALLS", "android.permission.READ_PHONE_STATE"))
        assertEquals(phone, AppPermissions.ask(row("android.permission.ANSWER_PHONE_CALLS"), sdk, false))
        assertEquals(phone, AppPermissions.ask(row("android.permission.READ_PHONE_STATE"), sdk, false))
        assertEquals(Ask.Runtime(listOf("android.permission.POST_NOTIFICATIONS")), AppPermissions.ask(row("android.permission.POST_NOTIFICATIONS"), sdk, false))
    }

    @Test
    fun `location is asked only when the rider allowed REau to use it`() {
        assertNull(AppPermissions.ask(row("android.permission.ACCESS_FINE_LOCATION"), sdk, locationAllowed = false))
        assertEquals(
            Ask.Runtime(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")),
            AppPermissions.ask(row("android.permission.ACCESS_COARSE_LOCATION"), sdk, locationAllowed = true),
        )
    }

    @Test
    fun `the special accesses go to their own places`() {
        assertEquals(Ask.Accessibility, AppPermissions.ask(row(AppPermissions.ACCESSIBILITY), sdk, false))
        assertEquals(Ask.Battery, AppPermissions.ask(row(AppPermissions.BATTERY), sdk, false))
    }

    @Test
    fun `nothing is asked for what is granted, granted at install, a library's, or not in this Android`() {
        assertNull(AppPermissions.ask(row("android.permission.RECORD_AUDIO", granted = true), sdk, false))
        assertNull(AppPermissions.ask(row("android.permission.INTERNET"), sdk, false))
        assertNull(AppPermissions.ask(row("com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), sdk, false))
        assertNull("Android 12 has no notification permission", AppPermissions.ask(row("android.permission.POST_NOTIFICATIONS"), 32, false))
    }

    @Test
    fun `asking for all that is missing joins the runtime ones in one dialog, each once`() {
        val rows = listOf(
            row(AppPermissions.ACCESSIBILITY), row("android.permission.ANSWER_PHONE_CALLS"), row("android.permission.READ_PHONE_STATE"),
            row("android.permission.RECORD_AUDIO", granted = true), row("android.permission.POST_NOTIFICATIONS"), row("android.permission.INTERNET"),
        )
        assertEquals(
            listOf("android.permission.ANSWER_PHONE_CALLS", "android.permission.READ_PHONE_STATE", "android.permission.POST_NOTIFICATIONS"),
            AppPermissions.askAll(rows, sdk, locationAllowed = false),
        )
    }

    private val all = listOf(
        row(AppPermissions.BATTERY, granted = true), row("android.permission.ANSWER_PHONE_CALLS"), row("android.permission.READ_PHONE_STATE"),
        row("android.permission.RECORD_AUDIO"), row("android.permission.POST_NOTIFICATIONS", granted = true), row("android.permission.INTERNET"),
    )

    @Test
    fun `with the joystick call and Gemini switches off, their permissions are optional and nothing is missing`() {
        val off = AppPermissions.Functions(answerCalls = false, gemini = false)
        assertEquals(0, AppPermissions.missing(all, sdk, locationAllowed = false, functions = off))
        assertEquals(emptyList<String>(), AppPermissions.askAll(all, sdk, locationAllowed = false, functions = off))
        assertEquals(true, AppPermissions.optional(row("android.permission.ANSWER_PHONE_CALLS"), off, false))
        assertEquals(true, AppPermissions.optional(row("android.permission.READ_PHONE_STATE"), off, false))
        assertEquals(true, AppPermissions.optional(row("android.permission.RECORD_AUDIO"), off, false))
        assertEquals("a granted one is not told apart by the page", false, AppPermissions.optional(row("android.permission.POST_NOTIFICATIONS"), off, false))
        assertEquals(false, AppPermissions.optional(row("android.permission.INTERNET"), off, false))
    }

    @Test
    fun `each switch brings back only its own permissions`() {
        val calls = AppPermissions.Functions(answerCalls = true, gemini = false)
        assertEquals(2, AppPermissions.missing(all, sdk, false, calls))
        assertEquals(listOf("android.permission.ANSWER_PHONE_CALLS", "android.permission.READ_PHONE_STATE"), AppPermissions.askAll(all, sdk, false, calls))
        val gemini = AppPermissions.Functions(answerCalls = false, gemini = true)
        assertEquals(1, AppPermissions.missing(all, sdk, false, gemini))
        assertEquals(listOf("android.permission.RECORD_AUDIO"), AppPermissions.askAll(all, sdk, false, gemini))
        // Everything on (and the default): the three of the real session count again.
        assertEquals(3, AppPermissions.missing(all, sdk, false))
        assertEquals(3, AppPermissions.askAll(all, sdk, false).size)
    }

    @Test
    fun `the missing count equals what the page can ask, the special accesses included`() {
        val rows = all + row(AppPermissions.ACCESSIBILITY)
        assertEquals(4, AppPermissions.missing(rows, sdk, false))
        assertEquals("only the accessibility, a special row with its own button", 1, AppPermissions.missing(rows, sdk, false, AppPermissions.Functions(false, false)))
        // Location: not allowed by the rider, not missing; allowed, both rows count.
        val location = listOf(row("android.permission.ACCESS_FINE_LOCATION"), row("android.permission.ACCESS_COARSE_LOCATION"))
        assertEquals(0, AppPermissions.missing(location, sdk, locationAllowed = false))
        assertEquals(2, AppPermissions.missing(location, sdk, locationAllowed = true))
        // Android 12 has no notification permission to miss.
        assertEquals(0, AppPermissions.missing(listOf(row("android.permission.POST_NOTIFICATIONS")), 32, false))
    }

    @Test
    fun `the functions come from the settings`() {
        val settings = REauSettings(answerCalls = false, gemini = true)
        assertEquals(AppPermissions.Functions(answerCalls = false, gemini = true), AppPermissions.functionsOf(settings))
    }
}
