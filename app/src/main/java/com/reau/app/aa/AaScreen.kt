package com.reau.app.aa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which of Android Auto's screens is shown: its app menu, any app, or not known. */
enum class AaScreen { MENU, APPS, UNKNOWN }

/**
 * As measured on a device, Android Auto 17.9 draws
 * each of its screens on a virtual display of its own, all open at once ("GhostActivityDisplay:ComponentInfo{…}"); the
 * one shown is the one with the focused window. The app menu is GhAppLauncherService's display; any other of Android
 * Auto's displays is an app (Waze, Google Maps, Spotify, the phone). Only the name counts: the numbers change each session.
 */
object AaScreenRule {
    /** A display: its number, its name (DisplayManager) and whether one of its windows has focus. */
    data class Display(val id: Int, val name: String, val focused: Boolean)

    const val PROJECTED = "GhostActivityDisplay"
    const val LAUNCHER = "GhAppLauncherService"

    /**
     * The apps whose screen is a display of their own process
     * (Google Maps' GmmCarProjectionService), not of Android Auto's: the accessibility service hears their window
     * changes too (res/xml/aa_server_accessibility.xml), or it never learns that their screen took the focus (measured
     * on a phone: Maps took it and REau heard nothing, so the menu's fit stayed on the map).
     */
    val OWN_DISPLAY_APPS: Set<String> = setOf("com.google.android.apps.maps")

    /** The screen the focused display of Android Auto shows; UNKNOWN with none (the phone's screen, the side bar). */
    fun of(displays: List<Display>, focusedBefore: Set<Int> = emptySet(), shownBefore: Int? = null): AaScreen =
        screenOf(shown(displays, focusedBefore, shownBefore))

    fun screenOf(display: Display?): AaScreen = display?.let { if (LAUNCHER in it.name) AaScreen.MENU else AaScreen.APPS } ?: AaScreen.UNKNOWN

    /**
     * The focused display of Android Auto, if any. Since Google Maps is heard its display may stay focused while
     * another takes the focus too: the one not focused in the reading before
     * ([focusedBefore]) has just taken it and wins; with none new, the one shown before ([shownBefore]) while it keeps
     * the focus; else the newest display.
     */
    fun shown(displays: List<Display>, focusedBefore: Set<Int> = emptySet(), shownBefore: Int? = null): Display? {
        val focused = displays.filter { it.focused && PROJECTED in it.name }
        return focused.lastOrNull { it.id !in focusedBefore } ?: focused.firstOrNull { it.id == shownBefore } ?: focused.lastOrNull()
    }

    /** The numbers of Android Auto's focused displays in a reading. */
    fun focusedIds(displays: List<Display>): Set<Int> = displays.filter { it.focused && PROJECTED in it.name }.map { it.id }.toSet()

    /** "GhAppLauncherService" out of "GhostActivityDisplay:ComponentInfo{…/….GhAppLauncherService}". */
    fun shortName(name: String): String = name.substringAfterLast('.').substringBefore('}')
}

/** The one watcher of the process, fed by the accessibility service and read by the projection. */
object AaScreens {
    /** How long a new screen must hold before it counts. */
    const val HOLD_MS = 400L

    val watch = AaScreenWatch(HOLD_MS) { com.reau.app.data.RideDiagnostics.log("aa-screen", it) }

    /**
     * Who reads Android Auto's screen again without a window event
     * ([AaScreenRereads]); the accessibility service while it is connected.
     */
    interface Rereader {
        /** An input was just sent to Android Auto. */
        fun inputSent()

        /** The projection (Android Auto's side) started or stopped. */
        fun projection(on: Boolean)
    }

    @Volatile var rereader: Rereader? = null

    /** Whether the projection runs now, for a re-reader that connects while it does. */
    @Volatile var projecting = false
        private set

    /** An input was sent to Android Auto (AaReceiver.sendInput). */
    fun inputSent() {
        rereader?.inputSent()
    }

    /** The joystick's select (KEY_ENTER) was sent to Android Auto (AaReceiver.sendInput); see [AaScreenWatch.selectSent]. */
    fun selectSent() {
        watch.selectSent()
    }

    /** Android Auto's side of the projection started or stopped (LiveSides). */
    fun projection(on: Boolean) {
        projecting = on
        rereader?.projection(on)
    }
}

/**
 * The screen shown, taken only once it held for [holdMs] (scrolling the menu or a window opening on top must not make the
 * fit blink); an unknown reading keeps the last screen. Each change is said once through [say], for the ride log.
 */
class AaScreenWatch(private val holdMs: Long, private val say: (String) -> Unit) {
    private val current = MutableStateFlow(AaScreen.UNKNOWN)
    val screen: StateFlow<AaScreen> = current.asStateFlow()

    // The short name of the display shown ("TemplateNavigationService", ...), after the same hold; it
    // also changes from one app to another, which [screen] does not.
    private val currentName = MutableStateFlow<String?>(null)
    val shownName: StateFlow<String?> = currentName.asStateFlow()
    private var pendingName: String? = null
    private var nameSince = 0L

    private var pending: AaScreen? = null
    private var since = 0L

    // The change waiting was first seen by a re-read (AaScreenRereads), not by a window event
    private var pendingByReread = false

    // The menu was left by a select ([selectSent]): the launcher's display keeps its focus, so it only counts again once it
    // takes the focus anew.
    private var leftBySelect = false
    private var focusedBefore = emptySet<Int>()
    private var shownId: Int? = null

    /** A change was seen and waits for [holdMs]: read again then (the system says a window change only once). */
    val waiting: Boolean get() = pending != null || pendingName != null

    /**
     * The joystick's select was sent with the menu shown: in the menu it always opens something (an app, Settings, Exit),
     * so the apps' screen is taken at once. Android Auto keeps the launcher's display focused after a select (the
     * menu's fit then stayed on Waze's map for minutes), so from then on a reading of the menu counts only when its
     * display takes the focus anew (it was not focused in the reading before), as it does when the menu really opens.
     * Nothing with any other screen shown.
     */
    @Synchronized
    fun selectSent() {
        if (current.value != AaScreen.MENU) return
        leftBySelect = true
        pending = null
        current.value = AaScreen.APPS
        say("aa-screen: apps (a select in the menu)")
    }

    /**
     * A new reading at [nowMs]; the screen it changed to, or null when nothing changed. [reread]: the reading was not
     * caused by a window event, which the log line of the change says.
     */
    @Synchronized
    fun update(displays: List<AaScreenRule.Display>, nowMs: Long, reread: Boolean = false): AaScreen? {
        // Which display has just taken the focus, against the reading before
        val candidate = AaScreenRule.shown(displays, focusedBefore, shownId)
        val wasFocused = focusedBefore
        focusedBefore = AaScreenRule.focusedIds(displays)
        if (candidate != null) shownId = candidate.id
        followName(candidate?.name?.let(AaScreenRule::shortName), nowMs)
        var seen = AaScreenRule.screenOf(candidate)
        if (leftBySelect && seen != AaScreen.UNKNOWN) {
            // an app's display, or the launcher's taking the focus anew (the menu opened again), ends the wait
            if (seen == AaScreen.MENU && candidate!!.id in wasFocused) seen = AaScreen.UNKNOWN else leftBySelect = false
        }
        if (seen == AaScreen.UNKNOWN || seen == current.value) {
            pending = null
        } else if (pending != seen) {
            pending = seen
            since = nowMs
            pendingByReread = reread
        }
        val held = pending?.takeIf { nowMs - since >= holdMs } ?: return null
        pending = null
        current.value = held
        val shown = candidate
        val by = if (pendingByReread) " by re-read" else ""
        say("aa-screen: ${held.name.lowercase()} (display ${shown?.id}, ${shown?.name?.let(AaScreenRule::shortName)})$by")
        return held
    }

    /** [name] shown at [nowMs] becomes [shownName] once it held; none of Android Auto's keeps the name. */
    private fun followName(name: String?, nowMs: Long) {
        if (name == null || name == currentName.value) {
            pendingName = null
        } else if (pendingName != name) {
            pendingName = name
            nameSince = nowMs
        }
        if (pendingName != null && nowMs - nameSince >= holdMs) {
            currentName.value = pendingName
            pendingName = null
        }
    }
}
