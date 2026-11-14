package com.reau.app.aa

import com.reau.app.video.PictureLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which of Android Auto's screens is shown: its app menu, a navigation app (Waze, Google Maps), any other app (Spotify, the phone…), or
 * not known. Each of the first three has its own crop. REau tells them by the picture alone ([AaScreenWatch.look], the little net of
 * [PictureLook]); the keys and taps it sends to Android Auto ([AaScreens]) only say what the picture is about to show.
 */
enum class AaScreen { MENU, NAV, APPS, UNKNOWN }

/** The one watcher of the process, fed by the picture's looks and by the inputs sent to Android Auto, and read by the projection. */
object AaScreens {
    val watch = AaScreenWatch { com.reau.app.data.RideDiagnostics.log("aa-screen", it) }

    /** An input was sent to Android Auto (AaReceiver.sendInput). */
    fun inputSent() {
        com.reau.app.stream.InputLatency.keySent()
        com.reau.app.stream.InputBurst.sent(android.os.SystemClock.uptimeMillis())
    }

    /** The joystick's select (KEY_ENTER) was sent to Android Auto (AaReceiver.sendInput); see [AaScreenWatch.selectSent]. */
    fun selectSent() {
        watch.selectSent(android.os.SystemClock.elapsedRealtime())
    }

    /**
     * A tap on the phone's screen reached Android Auto at ([x], [y]) of its video (AaReceiver.sendInput): a select when the menu is shown, and on the
     * side bar's shortcuts the same wait with an app shown ([AaScreenWatch.selectSent] with a rail slot).
     */
    fun tapSent(x: Int, y: Int, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        AaRail.slot(x, y)?.let { watch.selectSent(nowMs, rail = it) }
        watch.selectSent(nowMs)
    }

    /** The Home key (KEY_HOME) was sent to Android Auto (AaReceiver.sendInput); see [AaScreenWatch.homeSent]. */
    fun homeSent() {
        watch.homeSent(android.os.SystemClock.elapsedRealtime())
    }

    /** Android Auto's side of the projection started or stopped (LiveSides). */
    fun projection(on: Boolean) {
        // The next session starts from nothing: a screen left over from this one (the menu, say) must not shape its first moments.
        if (!on) watch.reset()
    }
}

/**
 * The screen shown, said by the picture ([look]) and by the keys sent ([homeSent], [selectSent]); an unsure look keeps the last screen. Each
 * change is said once through [say], for the ride log.
 */
class AaScreenWatch(private val say: (String) -> Unit) {
    private val current = MutableStateFlow(AaScreen.UNKNOWN)
    val screen: StateFlow<AaScreen> = current.asStateFlow()

    // What the picture says ([look]) once its first moments have passed, and the settle waits that a select or the Home key start.
    private val picture = PictureGate()

    // What the last look of the picture was: the media shortcut of the side bar goes by it ([selectSent])
    private var lastKind: PictureLook.Kind? = null
    private var firstLookMs = 0L
    private var listening = false

    /** Forgets the screen shown and everything waiting: the projection ended, the next session knows nothing yet. */
    @Synchronized
    fun reset() {
        picture.reset()
        lastKind = null
        firstLookMs = 0L
        listening = false
        current.value = AaScreen.UNKNOWN
    }

    /**
     * The joystick's select was sent with the menu shown: in the menu it always opens something (an app, Settings, Exit),
     * so the apps' screen is taken at once, and the picture says which one it was (a map: the navigation).
     *
     * [rail]: a tap on a shortcut of the side bar ([AaRail]) with an app shown (22/10/2026, the Spotify shortcut with Spotify in front: Android Auto went back to the
     * map and the apps' crop stayed 15 s on Google Maps' picture). The shortcut's app opens, and the one already in front sends Android Auto back to the map, so the
     * screen goes through the same wait; nothing in the menu or in the navigation. The media shortcut means the map only when the last look was of a lit
     * picture (Spotify's): Settings shares Spotify's look and is dark, so with a dark or no look before it the media shortcut opens Spotify, and the screen stays the apps'.
     */
    @Synchronized
    fun selectSent(nowMs: Long = 0L, rail: AaRail.Slot? = null) {
        if (current.value != if (rail != null) AaScreen.APPS else AaScreen.MENU) return
        if (rail == AaRail.Slot.MEDIA && lastKind != PictureLook.Kind.MAP) return
        picture.arm(nowMs + PictureGate.SETTLE_MS)
        current.value = AaScreen.APPS
        say(if (rail != null) "aa-screen: a tap on the side bar's shortcuts, the picture says what it picked" else "aa-screen: apps (a select in the menu)")
    }

    /**
     * The Home key was sent: Android Auto opens its app menu, taken at once, and it stays until the picture says another screen or a select or a tap
     * leaves it ([selectSent]). Nothing when the menu is already shown.
     */
    @Synchronized
    fun homeSent(nowMs: Long = 0L) {
        // The map stays on the screen for a moment after the key: looks of it are no news (25/10/2026: the crop went back to the
        // navigation's for 1.5 s after each Home key, from two looks of the map that was still leaving).
        picture.holdMaps(nowMs + PictureGate.SETTLE_MS)
        if (current.value == AaScreen.MENU) return
        current.value = AaScreen.MENU
        say("aa-screen: menu (the Home key)")
    }

    /**
     * What Android Auto's picture looks like at [nowMs] ([PictureLook]); the screen it changed to, or null. The first [FIRST_LOOKS_MS] of a
     * session are not read (a loading screen is no screen). Each change, and the first look that agrees with the screen, is said with what it saw.
     */
    @Synchronized
    fun look(kind: PictureLook.Kind, nowMs: Long): AaScreen? {
        lastKind = kind
        if (firstLookMs == 0L) firstLookMs = nowMs
        if (!listening) {
            if (nowMs - firstLookMs < FIRST_LOOKS_MS) return null
            listening = true
            picture.restart()
            say("aa-screen: the picture says the screen (${FIRST_LOOKS_MS} ms after the first look)")
        }
        val shown = current.value
        val verdict = picture.next(kind, nowMs, shown)
        val to = verdict?.changeTo
        if (verdict != null && to == null) say("aa-screen: ${shown.name.lowercase()} confirmed by the picture (${verdict.saw})")
        if (to != null) {
            current.value = to
            say("aa-screen: ${to.name.lowercase()} (by the picture: ${verdict.saw}, from ${shown.name.lowercase()})")
        }
        return to
    }

    private companion object {
        const val FIRST_LOOKS_MS = 3_000L
    }
}

/**
 * What the picture does to the screen ([AaScreenWatch.look]): two looks of a map make the screen the navigation's, and three of the menu, or
 * three of an app, make it the menu or the apps. A look that is not sure starts the count over. A select ([arm]) and the Home key ([holdMaps])
 * make it wait a moment first: what is still leaving the screen is the old picture, not a new one.
 */
internal class PictureGate {
    /** What a look made of the screen: [changeTo] it, or null when it only confirms the screen ([saw] says what the picture was). */
    class Verdict(val changeTo: AaScreen?, val saw: String)

    // Looks that are not of a map before this time do not count: after a select the first moments still show the menu.
    private var fromMs = 0L

    // Looks of a map before this time do not count: it is the one that was in front when the Home key was sent, still leaving.
    private var mapsFromMs = 0L
    private var maps = 0
    private var menus = 0
    private var apps = 0
    private var confirmed = false

    /** A select was sent: looks that are not of a map count only from [fromMs] on. */
    fun arm(fromMs: Long) {
        this.fromMs = fromMs
        restart()
    }

    /** The Home key was sent: looks of a map before [untilMs] are the old screen's, not a new map. */
    fun holdMaps(untilMs: Long) {
        mapsFromMs = untilMs
        restart()
    }

    /** A new session: the waits of the last one and the looks counted are forgotten. */
    fun reset() {
        fromMs = 0L
        mapsFromMs = 0L
        restart()
    }

    /** The looks counted start over (a look changed the screen, or one was not sure). */
    fun restart() {
        maps = 0
        menus = 0
        apps = 0
        confirmed = false
    }

    /** What the look [kind] at [nowMs] does to the [shown] screen. */
    fun next(kind: PictureLook.Kind, nowMs: Long, shown: AaScreen): Verdict? {
        if (!counts(kind, nowMs)) return null
        when (kind) {
            PictureLook.Kind.MAP -> { maps++; menus = 0; apps = 0 }
            PictureLook.Kind.MENU -> { menus++; maps = 0; apps = 0 }
            PictureLook.Kind.APPS -> { apps++; maps = 0; menus = 0 }
            PictureLook.Kind.UNSURE -> restart()
        }
        return verdict(shown)
    }

    /**
     * Whether a look of [kind] at [nowMs] counts. After a select the first moments still show the menu, which no look takes for a map: only the looks
     * that are not of a map wait for the settle, so the map of the app picked comes in as soon as it is drawn (it took 0.8 s more, 25/10/2026).
     * After the Home key it is the other way round: the map leaving the screen is the old one ([holdMaps]).
     */
    private fun counts(kind: PictureLook.Kind, nowMs: Long): Boolean =
        if (kind == PictureLook.Kind.MAP) nowMs >= mapsFromMs else nowMs >= fromMs

    private fun verdict(shown: AaScreen): Verdict? {
        val mapNow = maps >= MAP_LOOKS
        val menuNow = menus >= OTHER_LOOKS
        val appsNow = apps >= OTHER_LOOKS
        return when {
            mapNow && shown != AaScreen.NAV -> changed(AaScreen.NAV, "a map")
            menuNow && shown != AaScreen.MENU -> changed(AaScreen.MENU, "the app menu")
            appsNow && shown != AaScreen.APPS -> changed(AaScreen.APPS, "an app")
            confirmed -> null
            mapNow -> confirm("a map")
            menuNow -> confirm("the app menu")
            appsNow -> confirm("an app")
            else -> null
        }
    }

    private fun changed(to: AaScreen, saw: String): Verdict {
        restart()
        return Verdict(to, saw)
    }

    private fun confirm(saw: String): Verdict {
        confirmed = true
        return Verdict(null, saw)
    }

    companion object {
        const val SETTLE_MS = 800L
        const val MAP_LOOKS = 2
        const val OTHER_LOOKS = 3
    }
}
