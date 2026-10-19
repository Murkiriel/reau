package com.reau.app.settings

import androidx.annotation.StringRes
import com.reau.app.R

/**
 * The permissions page: every permission REau asks for, what it is for, and whether it
 * is granted now. Two special accesses come first, because the rider switches them by hand and REau depends on them:
 * the accessibility service (which also reads which Android Auto screen is shown, for the app menu's own fit) and the
 * battery optimization exemption. Then the ones that depend on the rider, the ones Android grants at install, and
 * last any the build adds that REau does not know (a library's), with the library text.
 */
object AppPermissions {
    enum class Kind { SPECIAL, ASKED, INSTALL, LIBRARY }

    data class Explained(val permission: String, val kind: Kind, @param:StringRes val title: Int, @param:StringRes val why: Int)

    /**
     * [stuck]: a special access switched on that Android does not run ("não está funcionando"). [restricted]: the
     * accessibility off where Android may block it ("Configuração restrita", [restrictedSettings]).
     */
    data class Row(val explained: Explained, val granted: Boolean, val stuck: Boolean = false, val restricted: Boolean = false)

    const val ACCESSIBILITY = "special:accessibility"
    const val BATTERY = "special:battery"

    private const val P = "android.permission."

    /**
     * In the order the page shows them: inside each kind, by how much REau's work depends on the permission, what
     * stops the projection first and what only serves an optional switch last. Two names may share one explanation
     * (each still gets its own row).
     */
    private val catalog: List<Explained> = listOf(
        Explained(ACCESSIBILITY, Kind.SPECIAL, R.string.perm_accessibility_title, R.string.perm_accessibility_why),
        Explained(BATTERY, Kind.SPECIAL, R.string.perm_battery_title, R.string.perm_battery_why),
        Explained(P + "ACCESS_FINE_LOCATION", Kind.ASKED, R.string.perm_location_title, R.string.perm_location_why),
        Explained(P + "ACCESS_COARSE_LOCATION", Kind.ASKED, R.string.perm_location_title, R.string.perm_location_why),
        Explained(P + "POST_NOTIFICATIONS", Kind.ASKED, R.string.perm_notifications_title, R.string.perm_notifications_why),
        Explained(P + "ANSWER_PHONE_CALLS", Kind.ASKED, R.string.perm_answer_calls_title, R.string.perm_answer_calls_why),
        Explained(P + "READ_PHONE_STATE", Kind.ASKED, R.string.perm_phone_state_title, R.string.perm_phone_state_why),
        Explained(P + "RECORD_AUDIO", Kind.ASKED, R.string.perm_microphone_title, R.string.perm_microphone_why),
        Explained(P + "INTERNET", Kind.INSTALL, R.string.perm_internet_title, R.string.perm_internet_why),
        Explained(P + "FOREGROUND_SERVICE", Kind.INSTALL, R.string.perm_foreground_title, R.string.perm_foreground_why),
        Explained(P + "FOREGROUND_SERVICE_CONNECTED_DEVICE", Kind.INSTALL, R.string.perm_foreground_device_title, R.string.perm_foreground_device_why),
        Explained(P + "CHANGE_NETWORK_STATE", Kind.INSTALL, R.string.perm_change_network_title, R.string.perm_change_network_why),
        Explained(P + "ACCESS_NETWORK_STATE", Kind.INSTALL, R.string.perm_network_state_title, R.string.perm_network_state_why),
        Explained(P + "ACCESS_WIFI_STATE", Kind.INSTALL, R.string.perm_wifi_state_title, R.string.perm_wifi_state_why),
        Explained(P + "CHANGE_WIFI_STATE", Kind.INSTALL, R.string.perm_change_wifi_title, R.string.perm_change_wifi_why),
        Explained(P + "WAKE_LOCK", Kind.INSTALL, R.string.perm_wake_lock_title, R.string.perm_wake_lock_why),
        Explained(P + "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", Kind.INSTALL, R.string.perm_battery_request_title, R.string.perm_battery_request_why),
        Explained(P + "FOREGROUND_SERVICE_MICROPHONE", Kind.INSTALL, R.string.perm_foreground_mic_title, R.string.perm_foreground_mic_why),
        Explained(P + "MODIFY_AUDIO_SETTINGS", Kind.INSTALL, R.string.perm_audio_settings_title, R.string.perm_audio_settings_why),
    )
    private val byName = catalog.associateBy { it.permission }

    /** How a row's permission is asked for: Android's dialog, the accessibility settings, or the battery request. */
    sealed interface Ask {
        data class Runtime(val permissions: List<String>) : Ask
        data object Accessibility : Ask
        data object Battery : Ask
    }

    private const val TIRAMISU = 33
    private const val MICROPHONE = P + "RECORD_AUDIO"
    private val PHONE = listOf(P + "ANSWER_PHONE_CALLS", P + "READ_PHONE_STATE")
    private val LOCATION = listOf(P + "ACCESS_FINE_LOCATION", P + "ACCESS_COARSE_LOCATION")
    private val SINCE_TIRAMISU = setOf(P + "POST_NOTIFICATIONS")

    /**
     * How to ask for [row]'s permission, or null when there is nothing to ask (granted,
     * granted at install, a library's, not in this Android [sdk], or location while the rider has not allowed REau to
     * use it). The two phone permissions go together, and so do the two location ones.
     */
    fun ask(row: Row, sdk: Int, locationAllowed: Boolean): Ask? {
        val name = row.explained.permission
        return when {
            row.granted -> null
            name == ACCESSIBILITY -> Ask.Accessibility
            name == BATTERY -> Ask.Battery
            row.explained.kind != Kind.ASKED -> null
            name in SINCE_TIRAMISU && sdk < TIRAMISU -> null
            name in PHONE -> Ask.Runtime(PHONE)
            name in LOCATION -> if (locationAllowed) Ask.Runtime(LOCATION) else null
            else -> Ask.Runtime(listOf(name))
        }
    }

    private const val PLAY_STORE = "com.android.vending"

    /**
     * From Android 13 an app installed from a file, not from a store, has its
     * accessibility switch blocked ("Configuração restrita") until "Permitir configurações restritas" in its app info.
     * REau never comes from the Play Store, so on Android 13+ the page says how to free it (Android says no more).
     */
    fun restrictedSettings(sdk: Int, installer: String?): Boolean = sdk >= TIRAMISU && installer != PLAY_STORE

    /**
     * Which of the settings' functions are on. The permissions of a function that is off stay on the page but are
     * optional: they are not counted as missing and not asked in bulk (each says "only with its switch").
     * Everything on by default, so the callers that do not know the settings keep asking for all.
     */
    data class Functions(val answerCalls: Boolean = true, val gemini: Boolean = true)

    /** The functions the rider's [settings] have on. */
    fun functionsOf(settings: REauSettings) = Functions(answerCalls = settings.answerCalls, gemini = settings.gemini)

    /**
     * Whether [row] belongs to a function that is off: the phone ones to "Atender ligação pelo joystick", the microphone
     * to "Gemini pelo joystick", the location ones to the rider's allowance of location ([locationAllowed]).
     */
    fun optional(row: Row, functions: Functions, locationAllowed: Boolean): Boolean = when (row.explained.permission) {
        in PHONE -> !functions.answerCalls
        MICROPHONE -> !functions.gemini
        in LOCATION -> !locationAllowed
        else -> false
    }

    /**
     * "Faltam N" of the main screen and of "Pedir as que faltam (N)": the rows that are not granted, that can be asked now
     * ([ask]) and whose function is on. The special accesses count too (each has its own button).
     */
    fun missing(rows: List<Row>, sdk: Int, locationAllowed: Boolean, functions: Functions = Functions()): Int =
        rows.count { !optional(it, functions, locationAllowed) && ask(it, sdk, locationAllowed) != null }

    /** "Pedir as que faltam": every runtime permission still missing and not optional, in one dialog, each once. */
    fun askAll(rows: List<Row>, sdk: Int, locationAllowed: Boolean, functions: Functions = Functions()): List<String> =
        rows.filterNot { optional(it, functions, locationAllowed) }
            .mapNotNull { ask(it, sdk, locationAllowed) as? Ask.Runtime }.flatMap { it.permissions }.distinct()

    /** What [permission] is for; one REau does not know is a library's (AndroidX adds its internal receiver one). */
    fun explain(permission: String): Explained =
        byName[permission] ?: Explained(permission, Kind.LIBRARY, R.string.perm_library_title, R.string.perm_library_why)

    /**
     * The page's rows: the two special accesses, then every permission in [requested] (what Android says the installed
     * app asks for), known ones in the catalog's order and the rest after them, each once.
     */
    @Suppress("LongParameterList") // what Android says of each special access, one fact a parameter, each with its default
    fun rows(
        requested: List<String>,
        granted: (String) -> Boolean,
        accessibilityOn: Boolean,
        batteryExempt: Boolean,
        accessibilityStuck: Boolean = false,
        accessibilityRestricted: Boolean = false,
        withAccessibility: Boolean = true,
    ): List<Row> {
        val accessibility = Row(
            explain(ACCESSIBILITY), accessibilityOn, stuck = accessibilityStuck && !accessibilityOn,
            restricted = accessibilityRestricted && !accessibilityOn,
        )
        val special = listOfNotNull(accessibility.takeIf { withAccessibility }, Row(explain(BATTERY), batteryExempt))
        val order = catalog.withIndex().associate { (i, e) -> e.permission to i }
        val asked = requested.distinct().sortedBy { order[it] ?: Int.MAX_VALUE }.map { Row(explain(it), granted(it)) }
        return special + asked
    }
}
