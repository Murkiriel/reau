package com.reau.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaVideo
import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.video.AaMargins
import com.reau.app.video.FrameFit
import com.reau.app.video.RectPx
import com.reau.app.video.StatsOverlay

/** How Android Auto's picture is fitted into the dash's dome. */
enum class Fit {
    /** The largest it can be inside the dome's box. */
    FILL_DOME,

    /** Smaller, so that none of it is hidden by the round edge. */
    WHOLE_VISIBLE,

    /** A part of the centre, [REauSettings.cropPercent] of the content, as large as the dome allows. */
    CROP,
}

/** Waze's map theme, through Android Auto's night sensor (service/DayNight.kt). */
/** Where Android Auto's sound goes while the browser switch is on: the phone only, the browser only, or both. */
enum class WebAudioMode { PHONE, BROWSER, BOTH }

enum class MapTheme {
    /** By the sun where the phone is. */
    AUTO,

    /** Always the day theme. */
    DAY,

    /** Always the night theme. */
    NIGHT,
}

/**
 * The six values of a fit (the apps' are [REauSettings]' own fields; the app menu may have its own). The
 * crop's place is the window's top left pixel in Android Auto's picture ([cropX], [cropY]); null, centred.
 */
data class FitParams(val fit: Fit, val cropPercent: Int, val cropX: Int?, val cropY: Int?, val moveX: Int, val moveY: Int)

/**
 * The rider's settings, read when the projection starts. The defaults (AppDefaultsTest): Android Auto (always
 * 800×480, [com.reau.app.aa.AaVideo]) at density 180 with no margins, the apps cropped to 74% and the app menu to 65%
 * in their own places, the dash's cards and maneuver on, the joystick's calls and Gemini on, Android Auto's server and
 * the bike's Wi-Fi by themselves; no debug recording and no detailed log. [cardImage] is the picture the card mode
 * sends (a content URI the rider picked, or the measuring grid built in, [DEFAULT_CARD]); null sends the legibility
 * card.
 *
 * The video to the dash (it lagged at speed with more) starts at a conservative envelope known to work on this dash: at most 4 frames a second ([fpsCeiling]; 2 still),
 * [streamBitrateBps] 200 kbps (100 still) at a constant [bitrateMode], a keyframe every [keyframeSec] second. Before,
 * 6 frames a second and always 1024 kbps, by the codec's own mode.
 */
data class REauSettings(
    val density: Int = DEFAULT_DENSITY,
    val marginW: Int = 0,
    val marginH: Int = 0,
    val fit: Fit = Fit.CROP,
    val cropPercent: Int = DEFAULT_CROP,
    /**
     * Which part of Android Auto's frame the crop takes, in pixels: the window's top left pixel in the picture, past
     * its edges by all the window but a pixel; null, centred.
     */
    val cropX: Int? = 0,
    val cropY: Int? = 70,
    val moveX: Int = 0,
    val moveY: Int = 0,
    val fpsCeiling: Int = DEFAULT_FPS_CEILING,
    /** The bitrate while the picture moves, half of it still; raised as needed so a higher rate keeps its bits per frame. */
    val streamBitrateBps: Int = DEFAULT_STREAM_BITRATE,
    val bitrateMode: BitrateMode = BitrateMode.CBR,
    /** 1 or 2 s, or 0: chosen by the stream itself (AutoKeyframeController), as it was before the fixed keyframe. */
    val keyframeSec: Int = DEFAULT_KEYFRAME_SEC,
    val clockSlowPercent: Int = 0,
    val recordVideo: Boolean = false,
    val recordSessions: Boolean = false,
    val cardImage: String? = DEFAULT_CARD,
    val mapTheme: MapTheme = MapTheme.AUTO,
    val maneuverData: Boolean = true,
    /**
     * The maneuver profile: near a maneuver (< 1000 m) the configured
     * [fpsCeiling]/[streamBitrateBps] apply in full; far, with a 200 m hysteresis, they halve. Needs [maneuverData]
     * on too (it is what feeds the maneuver distance from Android Auto's route); off by default.
     */
    val maneuverFpsProfile: Boolean = true,
    /**
     * The speed profile, a refinement of [maneuverFpsProfile] — slower (the route's own derived
     * speed, no GPS, [com.reau.app.stream.NavSpeed]) keeps the fuller profile even far from a maneuver; faster drops
     * it. Only has data in the same window as [maneuverFpsProfile] (same feed); when both are on and disagree,
     * [navProfileMode] decides. The km/h band is an estimate. Off by default.
     */
    val speedFpsProfile: Boolean = true,
    /**
     * With [maneuverFpsProfile] and [speedFpsProfile] both on and disagreeing, Otimista
     * takes whichever says the fuller profile (the profiles' own behaviour before this switch existed), Pessimista
     * whichever says the reduced one. Does nothing with zero or one of them on. Otimista by default.
     */
    val navProfileMode: NavProfileMode = NavProfileMode.PESSIMISTA,
    /** Android joins the bike's Wi-Fi by itself (a network suggestion), off by default. */
    val autoJoinWifi: Boolean = true,
    /** Dash packets and Android Auto messages go to the ride log, off by default. */
    val detailedLog: Boolean = false,
    /** The ride log (and the logcat copy) can be switched off on the screen; on by default. */
    val rideLog: Boolean = true,
    /** REau may use the phone's location (night mode, the dash's Wi-Fi name); off by default. */
    val useLocation: Boolean = false,
    /** Privacy: logs and recordings older than these days go by themselves; 0 is never. */
    val logKeepDays: Int = 30,
    /** The joystick answers and ends calls (asks for the phone permissions). */
    val answerCalls: Boolean = true,
    /** Touch Android Auto from the phone's "Tela do Android Auto" (announced next start). */
    val phoneTouch: Boolean = true,
    /** The song on the dash's own music card, off by default. */
    val dashMusic: Boolean = true,
    /** Who is calling on the dash's own call card, off by default. */
    val dashCaller: Boolean = true,
    /** A test: announce Android Auto's protocol 1.7 instead of 1.2, for the route's total. */
    val aaNewProtocol: Boolean = true,
    /** Up twice calls Gemini and Android Auto may open the microphone (asks for it). */
    val gemini: Boolean = true,
    /** The joystick page's choices, as [com.reau.app.aa.JoystickBindings.encode] writes them; empty, none. */
    val joystick: String = DEFAULT_JOYSTICK,
    /**
     * REau's accessibility service switches Android Auto's head unit
     * server on when the projection finds it off, and off when the projection ends; off by default.
     */
    val aaServerAuto: Boolean = true,
    /**
     * A minute after the joystick, one touch takes Android Auto's blue focus
     * away ([com.reau.app.aa.FocusClearer]); on by default. Taken at the next start.
     */
    val clearAaFocus: Boolean = true,
    /** The dash's mask over the "Tela do Android Auto" (video/DashMask). Off by default. */
    val dashMask: Boolean = true,
    /** The app menu's own fit; null, the apps'. */
    val menuFit: FitParams? = DEFAULT_MENU_FIT,
    /**
     * The menu's own fit as it was last set, kept while [menuFit] is off: switching it on again (a stray tap, say)
     * gives this back, never a copy of the apps' fit that happens to be on the screen. Follows [menuFit] while that is on
     * ([sanitized]); the default menu fit until one was ever set.
     */
    val menuFitKept: FitParams = DEFAULT_MENU_FIT,
    /**
     * The stream's FPS and bitrate written in red, with a black border, on the dash's
     * video ([StatsOverlay]); off by default, in the debug settings. Its place and size (in the dash frame's pixels)
     * are set on the "Tela do Android Auto", and only offered there while this is on.
     */
    val statsOverlay: Boolean = false,
    val statsX: Int = StatsOverlay.DEFAULT_X,
    val statsY: Int = StatsOverlay.DEFAULT_Y,
    val statsSize: Int = StatsOverlay.DEFAULT_SIZE,
    /**
     * The dash's heartbeat carries the phone's real battery, charging, GPS
     * and cell signal ([com.reau.app.service.PhoneStatusReader]) instead of the capture's 62 % and always-on GPS and
     * signal. On by default; the switch is there to go back if the joystick or the video ever act up with it.
     */
    val dashPhoneStatus: Boolean = true,
    /**
     * The album's picture on the dash's music card (JPEG 75x75, in slices), when Android
     * Auto hands it over. Off by default until it has been seen on the bike: it is the one thing here the dash was never
     * proved to accept.
     */
    val dashAlbumArt: Boolean = false,
    /**
     * The real time left, the maneuver after the next and the lost-signal icon on the dash; off until seen on the bike.
     */
    val dashRouteExtras: Boolean = false,
    /**
     * "Clima no painel", screen "Dados da moto": on, REau asks Open-Meteo (no key, no
     * account; the rounded place of "Usar a localização") for the weather and tells the dash the temperature (`06 10`) and
     * the icon (`06 08`). Off by default: it is the one thing REau sends to the internet. Off, the dash gets neither (no
     * made-up temperature).
     */
    val dashWeather: Boolean = false,
    /**
     * A server on the local network that shows Android Auto in a
     * browser ([com.reau.app.web.WebBridge]). Off by default, and only up while the projection runs.
     */
    val webView: Boolean = false,
    /** With [webView], the browser may also touch Android Auto. Off by default: the browser only looks. */
    val webTouch: Boolean = false,
    /**
     * Where Android Auto's sound goes while the browser switch is on: the phone only (as always: the navigation
     * voice reaches the rider's headset), the browser only, or both. Anything but the phone only needs the media and guidance sinks
     * announced to Android Auto ([audioSinks]).
     */
    val webAudio: WebAudioMode = WebAudioMode.PHONE,
) {
    /** The stats text as the compositor takes it. */
    val overlay: StatsOverlay get() = StatsOverlay(statsOverlay, statsX, statsY, statsSize)

    /** The apps' fit (Waze, Google Maps, Spotify...), the fields of before. */
    val appsFit: FitParams get() = FitParams(fit, cropPercent, cropX, cropY, moveX, moveY)

    fun withAppsFit(p: FitParams): REauSettings =
        copy(fit = p.fit, cropPercent = p.cropPercent, cropX = p.cropX, cropY = p.cropY, moveX = p.moveX, moveY = p.moveY)

    /** The part of Android Auto's frame left by the margins, where the fit takes the picture from. */
    val content: RectPx get() = AaMargins(marginW, marginH).contentRect(AaVideo.WIDTH, AaVideo.HEIGHT)

    /** The app menu's mapping: its own fit, or the apps'. */
    val menuMapping: FrameFit.Mapping get() = mappingOf(menuFit ?: appsFit)

    /** The mapping for the screen Android Auto shows (com.reau.app.aa.AaScreens); not known yet, the apps'. */
    fun mappingFor(screen: com.reau.app.aa.AaScreen): FrameFit.Mapping =
        if (screen == com.reau.app.aa.AaScreen.MENU) menuMapping else mapping

    /**
     * Whether Android Auto is told REau takes its sound: with any mode that is not the phone only, whatever the browser switch says. The
     * switch left out of this on purpose: the sinks are only told when a session begins, so tying them to the switch
     * took a new Android Auto session each time the switch went on or off.
     */
    val audioSinks: Boolean get() = webAudio != WebAudioMode.PHONE

    /**
     * With the sinks announced Android Auto no longer plays the sound itself, so REau plays it on the phone, unless the browser alone is to
     * have it (switch on): with the switch off nobody in a browser would hear it, and the phone must not go mute.
     */
    val soundOnPhoneViaReau: Boolean get() = audioSinks && (webAudio == WebAudioMode.BOTH || !webView)

    val aaConfig: AaConfig get() = AaConfig(
        density, AaMargins(marginW, marginH), navigation = maneuverData, touch = phoneTouch, audio = audioSinks,
        media = dashMusic, phone = dashCaller,
        protocolMinor = if (aaNewProtocol) com.reau.app.aa.Messages.MINOR_NEWER else com.reau.app.aa.Messages.MINOR_DEFAULT,
    )

    /**
     * Which part of Android Auto's frame goes where on the dash's frame: the fit, then the picture moved by
     * [moveX] (right) and [moveY] (down) dash pixels, negative for left and up.
     */
    val mapping: FrameFit.Mapping get() = mappingOf(appsFit)

    private fun mappingOf(p: FitParams): FrameFit.Mapping {
        val fitted = when (p.fit) {
            Fit.FILL_DOME -> FrameFit.fillDome(content)
            Fit.WHOLE_VISIBLE -> FrameFit.wholeVisible(content)
            Fit.CROP -> FrameFit.cropAt(content, p.cropPercent, p.cropX, p.cropY)
        }
        return FrameFit.moved(fitted, p.moveX, p.moveY)
    }

    /** Whether a running projection must be given a new [mapping] after these settings became [other]: the fit
     *  and everything that places the picture (to adjust it while looking at the dash). */
    fun refitNeeded(other: REauSettings): Boolean = mapping != other.mapping || menuMapping != other.menuMapping

    /**
     * What changed from these settings to [other], one "name: old -> new" per field (the log says which
     * setting the rider changed, from what to what). Read from the data class's own text, so a new field is covered
     * without a list to keep up to date. No setting holds a place or a route.
     */
    fun changesTo(other: REauSettings): List<String> =
        changes(fieldsOf(toString()) - KEPT_MENU_FIT, fieldsOf(other.toString()) - KEPT_MENU_FIT, "")

    /** A field that holds another data class on both sides (the menu's [FitParams]) is said field by field, as
     *  `menuFit.cropX`; a side with none (null) is said whole. */
    private fun changes(mine: Map<String, String>, theirs: Map<String, String>, prefix: String): List<String> =
        theirs.flatMap { (name, value) ->
            val old = mine[name]
            when {
                old == null || old == value -> emptyList()
                nested(old) && nested(value) -> changes(fieldsOf(old), fieldsOf(value), "$prefix$name.")
                else -> listOf("$prefix$name: $old -> $value")
            }
        }

    private fun nested(value: String) = value.firstOrNull()?.isUpperCase() == true && value.endsWith(")") && '(' in value

    /** The fields of a data class's text, split at the commas outside any inner one's parentheses. */
    internal fun fieldsOf(text: String): Map<String, String> {
        val body = text.substringAfter('(').removeSuffix(")")
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        body.forEachIndexed { i, c ->
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0 && body.startsWith(", ", i)) {
                parts += body.substring(start, i)
                start = i + 2
            }
        }
        parts += body.substring(start)
        return parts.mapNotNull { part ->
            part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it) to part.substring(it + 1) }
        }.toMap()
    }

    /**
     * Every value brought into what is offered. Two are no longer choices: the
     * video clock runs at 0% slower and touching Android Auto from the phone is native (the frame size and the
     * instrument cluster experiment went from the code itself). Four more are fixed, the stream's
     * rate ceiling, bitrate, bitrate mode and keyframe: profile 1 is 4 fps / 400 kbps / constant / 1 s and profile 2,
     * its half, 2 fps / 200 kbps (the navigation profiles choose between them, [DashConfig.PROFILE_FULL_FPS]), and the
     * maneuver data and the two navigation profiles (by maneuver, by speed) are always on, the pessimistic one winning when they disagree
     * ([navProfileMode]).
     */
    fun sanitized(): REauSettings {
        val framed = copy(
            clockSlowPercent = 0,
            phoneTouch = true,
            density = density.coerceIn(DENSITY),
            marginW = marginW.coerceIn(0, AaVideo.WIDTH - MIN_PICTURE),
            marginH = marginH.coerceIn(0, AaVideo.HEIGHT - MIN_PICTURE),
            navProfileMode = NavProfileMode.PESSIMISTA,
            maneuverData = true,
            clearAaFocus = true,
            aaNewProtocol = true,
            maneuverFpsProfile = true,
            speedFpsProfile = true,
            fpsCeiling = DashConfig.PROFILE_FULL_FPS,
            streamBitrateBps = DashConfig.PROFILE_FULL_BPS,
            bitrateMode = BitrateMode.CBR,
            keyframeSec = DashConfig.PROFILE_KEYFRAME_SEC,
            logKeepDays = logKeepDays.takeIf { it in LOG_KEEP_DAYS } ?: DEFAULT_LOG_KEEP_DAYS,
            statsX = statsX.coerceIn(StatsOverlay.X),
            statsY = statsY.coerceIn(StatsOverlay.Y),
            statsSize = statsSize.coerceIn(StatsOverlay.SIZE),
        )
        val content = framed.content
        val menu = framed.menuFit?.within(content)
        return framed.withAppsFit(appsFit.within(content)).copy(menuFit = menu, menuFitKept = menu ?: framed.menuFitKept.within(content))
    }

    /** A fit's values brought into what is offered; the crop's place within FrameFit.cropRange. */
    private fun FitParams.within(content: RectPx): FitParams {
        val percent = cropPercent.coerceIn(CROP)
        val (rangeX, rangeY) = FrameFit.cropRange(content, percent)
        return copy(
            cropPercent = percent,
            cropX = cropX?.coerceIn(rangeX),
            cropY = cropY?.coerceIn(rangeY),
            moveX = moveX.coerceIn(MOVE),
            moveY = moveY.coerceIn(MOVE),
        )
    }

    companion object {
        /** Privacy: the days offered for the logs, and 0 (never). */
        val LOG_KEEP_DAYS = listOf(7, 14, 30, 0)
        private const val DEFAULT_LOG_KEEP_DAYS = 30

        val DENSITY = 80..640
        val FPS = 4..30
        val CROP = 20..100

        // The crop and the move go one by one, for the finest fit. The crop's place in
        // pixels, one by one and ten by ten.
        const val STEP_CROP_POS = 1
        const val STEP_CROP_POS_BIG = 10
        const val STEP_CROP = 1

        /** How far the picture can be moved on the dash, each way, in dash pixels (the frame is 526×300). */
        val MOVE = -150..150
        val CLOCK = listOf(0, 3, 5, 8)
        const val STEP_DENSITY = 10
        const val STEP_MARGIN = 10
        const val STEP_MOVE = 1
        private const val DEFAULT_DENSITY = 180
        private const val DEFAULT_CROP = 74
        private const val DEFAULT_FPS_CEILING = DashConfig.PROFILE_FULL_FPS

        /** The bitrates offered: DashConfig's stops, 200 kbps to its 1024 kbps ceiling. */
        val BITRATES = DashConfig.STREAM_BITRATE_STEPS
        private const val DEFAULT_STREAM_BITRATE = DashConfig.PROFILE_FULL_BPS
        private const val DEFAULT_KEYFRAME_SEC = DashConfig.PROFILE_KEYFRAME_SEC

        /** The default joystick page. */
        const val DEFAULT_JOYSTICK = "media.left.once=PREVIOUS_SONG;media.right.once=NEXT_SONG;media.up.once=HOME;" +
            "media.down.once=ASSISTANT;media.center.once=PLAY_PAUSE;map.down.once=CONFIRM"

        /**
         * The default card, the 526×300 measuring grid, built in (res/drawable-nodpi). The package in it is the full
         * edition's: a card shown reads the app's own (LiveSides.cardPicture).
         */
        const val DEFAULT_CARD = "android.resource://com.reau.app/drawable/grade_medicao"

        /** The field of the menu fit kept while off: not said in the change lines, [menuFit] is. */
        private const val KEPT_MENU_FIT = "menuFitKept"

        /** The default app menu fit. */
        val DEFAULT_MENU_FIT = FitParams(Fit.CROP, 65, -80, -40, 8, 0)

        /** The least picture margins may leave on an axis. */
        private const val MIN_PICTURE = 16
    }
}

/** The menu's own fit switched [on] (the one kept, never a copy of the apps') or off (the apps' are used). */
fun REauSettings.withMenuFitOwn(on: Boolean): REauSettings = copy(menuFit = if (on) menuFitKept else null)

/** The menu tab's "restore": the menu's default fit, also as the one kept while its own fit is off. */
fun REauSettings.withMenuFitReset(): REauSettings =
    copy(menuFit = menuFit?.let { REauSettings.DEFAULT_MENU_FIT }, menuFitKept = REauSettings.DEFAULT_MENU_FIT)

/** Keeps [REauSettings] in the app's preferences. What is saved and what is loaded is always sanitized. */
class SettingsStore(private val prefs: SharedPreferences) {

    fun load(): REauSettings {
        conservativeVideoOnce()
        val loaded = loadAllButThePlaces()
        val content = loaded.content
        // The default place only while that fit was never saved; a crop saved centred has no place stored.
        val defaults = REauSettings()
        val appsDefault = defaults.appsFit.takeUnless { prefs.contains(KEY_FIT) }
        val menuDefault = defaults.menuFit.takeUnless { prefs.contains(KEY_MENU_FIT) }
        val kept = loaded.menuFitKept.placed(content, KEY_MENU_CROP_X, KEY_MENU_CROP_Y, KEY_MENU_CROP_CENTER_X, KEY_MENU_CROP_CENTER_Y, menuDefault)
        return loaded.withAppsFit(loaded.appsFit.placed(content, KEY_CROP_X, KEY_CROP_Y, KEY_CROP_CENTER_X, KEY_CROP_CENTER_Y, appsDefault))
            .copy(menuFit = kept.takeIf { loaded.menuFit != null }, menuFitKept = kept)
            .sanitized()
    }

    /**
     * A phone whose settings were saved by a version before the conservative video gets the conservative
     * video once (the defaults of [REauSettings]), with the slower Android Auto and the newer protocol switched off;
     * the rest stays as it was. Everything saved from then on carries the mark, and is kept.
     */
    private fun conservativeVideoOnce() {
        if (prefs.contains(KEY_CONSERVATIVE_VIDEO) || prefs.all.isEmpty()) return
        val d = REauSettings()
        prefs.edit {
            putVideo(d)
            putBoolean(KEY_AA_NEW_PROTOCOL, d.aaNewProtocol)
        }
    }

    /** The stream's own settings, with the mark that the conservative video was given. */
    private fun SharedPreferences.Editor.putVideo(s: REauSettings) {
        putInt(KEY_FPS, s.fpsCeiling)
        putInt(KEY_CLOCK, s.clockSlowPercent)
        putInt(KEY_BITRATE, s.streamBitrateBps)
        putString(KEY_BITRATE_MODE, s.bitrateMode.name)
        putInt(KEY_KEYFRAME, s.keyframeSec)
        putBoolean(KEY_CONSERVATIVE_VIDEO, true)
    }

    /**
     * The crop's place in pixels; saved before as a percent of the slack (the old keys), it is brought to
     * the same pixel FrameFit.centerCrop took, so nothing moves.
     */
    @Suppress("LongParameterList") // the two keys, the two old ones and the default: one call per fit
    private fun FitParams.placed(content: RectPx, keyX: String, keyY: String, oldX: String, oldY: String, default: FitParams?): FitParams {
        val (w, h) = FrameFit.cropSize(content, cropPercent)
        return copy(cropX = place(keyX, oldX, content.w - w, default?.cropX), cropY = place(keyY, oldY, content.h - h, default?.cropY))
    }

    /** With nothing stored, the default place (AppDefaultsTest), not the centre. */
    private fun place(key: String, old: String, slack: Int, default: Int?): Int? = when {
        prefs.contains(key) -> prefs.getInt(key, 0)
        prefs.contains(old) -> slack * prefs.getInt(old, 0) / PERCENT
        else -> default
    }

    /**
     * The app menu's own fit as stored; with nothing stored, [default] when [on]. With [on] null the switch is
     * not asked: the fit as it was kept, on or off.
     */
    private fun menuFitOr(default: FitParams, on: Boolean?): FitParams? =
        if (on != null && !prefs.getBoolean(KEY_MENU_FIT_ON, on)) null else FitParams(
            enumOr(prefs.getString(KEY_MENU_FIT, null), default.fit), prefs.getInt(KEY_MENU_CROP, default.cropPercent),
            null, null,
            prefs.getInt(KEY_MENU_MOVE_X, default.moveX), prefs.getInt(KEY_MENU_MOVE_Y, default.moveY),
        )



    private fun loadAllButThePlaces(): REauSettings {
        val defaults = REauSettings()
        return REauSettings(
            density = prefs.getInt(KEY_DENSITY, defaults.density),
            marginW = prefs.getInt(KEY_MARGIN_W, defaults.marginW),
            marginH = prefs.getInt(KEY_MARGIN_H, defaults.marginH),
            fit = enumOr(prefs.getString(KEY_FIT, null), defaults.fit),
            cropPercent = prefs.getInt(KEY_CROP, defaults.cropPercent),
            moveX = prefs.getInt(KEY_MOVE_X, defaults.moveX),
            moveY = prefs.getInt(KEY_MOVE_Y, defaults.moveY),
            menuFit = menuFitOr(defaults.menuFit ?: defaults.appsFit, defaults.menuFit != null),
            menuFitKept = menuFitOr(defaults.menuFitKept, on = null) ?: defaults.menuFitKept,
            fpsCeiling = prefs.getInt(KEY_FPS, defaults.fpsCeiling),
            streamBitrateBps = prefs.getInt(KEY_BITRATE, defaults.streamBitrateBps),
            bitrateMode = enumOr(prefs.getString(KEY_BITRATE_MODE, null), defaults.bitrateMode),
            keyframeSec = prefs.getInt(KEY_KEYFRAME, defaults.keyframeSec),
            clockSlowPercent = prefs.getInt(KEY_CLOCK, defaults.clockSlowPercent),
            recordVideo = prefs.getBoolean(KEY_RECORD_VIDEO, defaults.recordVideo),
            recordSessions = prefs.getBoolean(KEY_RECORD_SESSIONS, defaults.recordSessions),
            // Nothing stored is the built-in grid; the legibility card chosen is stored as "".
            cardImage = if (!prefs.contains(KEY_CARD_IMAGE)) defaults.cardImage else prefs.getString(KEY_CARD_IMAGE, null)?.ifEmpty { null },
            mapTheme = enumOr(prefs.getString(KEY_MAP_THEME, null), defaults.mapTheme),
            maneuverData = prefs.getBoolean(KEY_MANEUVER_DATA, defaults.maneuverData),
            maneuverFpsProfile = prefs.getBoolean(KEY_MANEUVER_FPS_PROFILE, defaults.maneuverFpsProfile),
            speedFpsProfile = prefs.getBoolean(KEY_SPEED_FPS_PROFILE, defaults.speedFpsProfile),
            navProfileMode = enumOr(prefs.getString(KEY_NAV_PROFILE_MODE, null), defaults.navProfileMode),
            autoJoinWifi = prefs.getBoolean(KEY_AUTO_JOIN_WIFI, defaults.autoJoinWifi),
            detailedLog = prefs.getBoolean(KEY_DETAILED_LOG, defaults.detailedLog),
            rideLog = prefs.getBoolean(KEY_RIDE_LOG, defaults.rideLog),
            useLocation = prefs.getBoolean(KEY_USE_LOCATION, defaults.useLocation),
            logKeepDays = prefs.getInt(KEY_LOG_KEEP_DAYS, defaults.logKeepDays),
            answerCalls = prefs.getBoolean(KEY_ANSWER_CALLS, defaults.answerCalls),
            phoneTouch = prefs.getBoolean(KEY_PHONE_TOUCH, defaults.phoneTouch),
            dashMusic = prefs.getBoolean(KEY_DASH_MUSIC, defaults.dashMusic),
            dashCaller = prefs.getBoolean(KEY_DASH_CALLER, defaults.dashCaller),
            gemini = prefs.getBoolean(KEY_GEMINI, defaults.gemini),
            aaNewProtocol = prefs.getBoolean(KEY_AA_NEW_PROTOCOL, defaults.aaNewProtocol),
            joystick = (prefs.getString(KEY_JOYSTICK, defaults.joystick) ?: defaults.joystick).let {
                // The map-mode choices saved before the one-tap joystick are forgotten, until the page is saved;
                // nothing saved is the default page, kept whole.
                val saved = prefs.contains(KEY_JOYSTICK)
                if (!saved || prefs.getBoolean(KEY_JOYSTICK_ONE_TAP, false)) it else com.reau.app.aa.JoystickBindings.withoutMapChoices(it)
            },
            aaServerAuto = prefs.getBoolean(KEY_AA_SERVER_AUTO, defaults.aaServerAuto),
            clearAaFocus = prefs.getBoolean(KEY_CLEAR_AA_FOCUS, defaults.clearAaFocus),
            dashMask = prefs.getBoolean(KEY_DASH_MASK, defaults.dashMask),
            statsOverlay = prefs.getBoolean(KEY_STATS_OVERLAY, defaults.statsOverlay),
            statsX = prefs.getInt(KEY_STATS_X, defaults.statsX),
            statsY = prefs.getInt(KEY_STATS_Y, defaults.statsY),
            statsSize = prefs.getInt(KEY_STATS_SIZE, defaults.statsSize),
            dashPhoneStatus = prefs.getBoolean(KEY_DASH_PHONE_STATUS, defaults.dashPhoneStatus),
            dashAlbumArt = prefs.getBoolean(KEY_DASH_ALBUM_ART, defaults.dashAlbumArt),
            dashRouteExtras = prefs.getBoolean(KEY_DASH_ROUTE_EXTRAS, defaults.dashRouteExtras),
            dashWeather = prefs.getBoolean(KEY_DASH_WEATHER, defaults.dashWeather),
            webView = prefs.getBoolean(KEY_WEB_VIEW, defaults.webView),
            webTouch = prefs.getBoolean(KEY_WEB_TOUCH, defaults.webTouch),
            webAudio = WebAudioMode.entries.firstOrNull { it.name == prefs.getString(KEY_WEB_AUDIO, null) } ?: defaults.webAudio,
        ).sanitized()
    }

    @Suppress("LongMethod") // one straight run of setup/serialization; cutting it adds indirection and no clarity
    fun save(settings: REauSettings) {
        val s = settings.sanitized()
        prefs.edit {
            // The frame size and the cluster experiment are no longer kept.
            remove(OLD_KEY_RESOLUTION)
            remove(OLD_KEY_CLUSTER)
            // Discarding the start of a stream was taken out; what it saved goes.
            remove(OLD_KEY_DISCARD_START)
            remove(OLD_KEY_REDUCTION)
            // The slower Android Auto was taken out; what it saved goes.
            remove(OLD_KEY_AA_SLOW_PACE)
            remove(OLD_KEY_AA_SLOW_PACE_FPS)
            putInt(KEY_DENSITY, s.density)
            putInt(KEY_MARGIN_W, s.marginW)
            putInt(KEY_MARGIN_H, s.marginH)
            putString(KEY_FIT, s.fit.name)
            putInt(KEY_CROP, s.cropPercent)
            putPlace(KEY_CROP_X, s.cropX)
            putPlace(KEY_CROP_Y, s.cropY)
            // The percents of before are read once, at the first load after the update
            remove(KEY_CROP_CENTER_X)
            remove(KEY_CROP_CENTER_Y)
            remove(KEY_MENU_CROP_CENTER_X)
            remove(KEY_MENU_CROP_CENTER_Y)
            putInt(KEY_MOVE_X, s.moveX)
            putInt(KEY_MOVE_Y, s.moveY)
            putBoolean(KEY_MENU_FIT_ON, s.menuFit != null)
            s.menuFitKept.let { m ->
                putString(KEY_MENU_FIT, m.fit.name)
                putInt(KEY_MENU_CROP, m.cropPercent)
                putPlace(KEY_MENU_CROP_X, m.cropX)
                putPlace(KEY_MENU_CROP_Y, m.cropY)
                putInt(KEY_MENU_MOVE_X, m.moveX)
                putInt(KEY_MENU_MOVE_Y, m.moveY)
            }
            putVideo(s)
            putBoolean(KEY_RECORD_VIDEO, s.recordVideo)
            putBoolean(KEY_RECORD_SESSIONS, s.recordSessions)
            putString(KEY_CARD_IMAGE, s.cardImage.orEmpty())
            putString(KEY_MAP_THEME, s.mapTheme.name)
            putBoolean(KEY_MANEUVER_DATA, s.maneuverData)
            putBoolean(KEY_MANEUVER_FPS_PROFILE, s.maneuverFpsProfile)
            putBoolean(KEY_SPEED_FPS_PROFILE, s.speedFpsProfile)
            putString(KEY_NAV_PROFILE_MODE, s.navProfileMode.name)
            putBoolean(KEY_AUTO_JOIN_WIFI, s.autoJoinWifi)
            putBoolean(KEY_DETAILED_LOG, s.detailedLog)
            putBoolean(KEY_RIDE_LOG, s.rideLog)
            putBoolean(KEY_USE_LOCATION, s.useLocation)
            putInt(KEY_LOG_KEEP_DAYS, s.logKeepDays)
            putBoolean(KEY_ANSWER_CALLS, s.answerCalls)
            putBoolean(KEY_PHONE_TOUCH, s.phoneTouch)
            putBoolean(KEY_DASH_MUSIC, s.dashMusic)
            putBoolean(KEY_DASH_CALLER, s.dashCaller)
            putBoolean(KEY_GEMINI, s.gemini)
            putBoolean(KEY_AA_NEW_PROTOCOL, s.aaNewProtocol)
            putString(KEY_JOYSTICK, s.joystick)
            putBoolean(KEY_JOYSTICK_ONE_TAP, true)
            putBoolean(KEY_AA_SERVER_AUTO, s.aaServerAuto)
            // The ring-hiding switches went (hiding the ring never worked on the dash); their old keys go with them.
            remove(KEY_HIDE_RING_MEDIA)
            remove(KEY_RING_ALWAYS_OFF)
            putBoolean(KEY_CLEAR_AA_FOCUS, s.clearAaFocus)
            putBoolean(KEY_DASH_MASK, s.dashMask)
            putBoolean(KEY_STATS_OVERLAY, s.statsOverlay)
            putInt(KEY_STATS_X, s.statsX)
            putInt(KEY_STATS_Y, s.statsY)
            putInt(KEY_STATS_SIZE, s.statsSize)
            putBoolean(KEY_DASH_PHONE_STATUS, s.dashPhoneStatus)
            putBoolean(KEY_DASH_ALBUM_ART, s.dashAlbumArt)
            putBoolean(KEY_DASH_ROUTE_EXTRAS, s.dashRouteExtras)
            putBoolean(KEY_DASH_WEATHER, s.dashWeather)
            putBoolean(KEY_WEB_VIEW, s.webView)
            putBoolean(KEY_WEB_TOUCH, s.webTouch)
            putString(KEY_WEB_AUDIO, s.webAudio.name)
        }
    }

    /** A crop's place, or none when it is centred. */
    private fun SharedPreferences.Editor.putPlace(key: String, place: Int?) {
        if (place == null) remove(key) else putInt(key, place)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val FILE = "settings"
        private const val OLD_KEY_RESOLUTION = "resolution"
        private const val KEY_DENSITY = "density"
        private const val KEY_MARGIN_W = "margin_w"
        private const val KEY_MARGIN_H = "margin_h"
        private const val KEY_FIT = "fit"
        private const val KEY_CROP = "crop_percent"
        private const val KEY_CROP_CENTER_X = "crop_center_x"
        private const val KEY_CROP_CENTER_Y = "crop_center_y"
        private const val KEY_MOVE_X = "move_x"
        private const val KEY_MOVE_Y = "move_y"
        private const val KEY_MENU_FIT_ON = "menu_fit_on"
        private const val KEY_MENU_FIT = "menu_fit"
        private const val KEY_MENU_CROP = "menu_crop_percent"
        private const val KEY_MENU_CROP_CENTER_X = "menu_crop_center_x"
        private const val KEY_MENU_CROP_CENTER_Y = "menu_crop_center_y"
        private const val KEY_CROP_X = "crop_x"
        private const val KEY_CROP_Y = "crop_y"
        private const val KEY_MENU_CROP_X = "menu_crop_x"
        private const val KEY_MENU_CROP_Y = "menu_crop_y"
        private const val PERCENT = 100
        private const val KEY_MENU_MOVE_X = "menu_move_x"
        private const val KEY_MENU_MOVE_Y = "menu_move_y"
        private const val KEY_FPS = "fps_ceiling"
        private const val KEY_BITRATE = "stream_bitrate"
        private const val KEY_BITRATE_MODE = "bitrate_mode"
        private const val KEY_KEYFRAME = "keyframe_sec"
        private const val OLD_KEY_REDUCTION = "reduction_filter"
        private const val OLD_KEY_DISCARD_START = "discard_start"
        private const val KEY_CONSERVATIVE_VIDEO = "conservative_video_20261010"
        private const val KEY_CLOCK = "clock_slow_percent"
        private const val KEY_RECORD_VIDEO = "record_video"
        private const val KEY_RECORD_SESSIONS = "record_sessions"
        private const val KEY_CARD_IMAGE = "card_image"
        private const val KEY_MAP_THEME = "map_theme"
        private const val KEY_MANEUVER_DATA = "maneuver_data"
        private const val KEY_MANEUVER_FPS_PROFILE = "maneuver_fps_profile"
        private const val KEY_SPEED_FPS_PROFILE = "speed_fps_profile"
        private const val KEY_NAV_PROFILE_MODE = "nav_profile_mode"
        private const val KEY_AUTO_JOIN_WIFI = "auto_join_wifi"
        private const val KEY_DETAILED_LOG = "detailed_log"
        private const val KEY_RIDE_LOG = "ride_log"
        private const val KEY_USE_LOCATION = "use_location"
        private const val KEY_LOG_KEEP_DAYS = "log_keep_days"
        private const val KEY_ANSWER_CALLS = "answer_calls"
        private const val KEY_PHONE_TOUCH = "phone_touch"
        private const val KEY_DASH_MUSIC = "dash_music"
        private const val KEY_DASH_CALLER = "dash_caller"
        private const val KEY_DASH_MASK = "dash_mask"
        private const val KEY_STATS_OVERLAY = "stats_overlay"
        private const val KEY_STATS_X = "stats_x"
        private const val KEY_STATS_Y = "stats_y"
        private const val KEY_STATS_SIZE = "stats_size"
        private const val KEY_DASH_PHONE_STATUS = "dash_phone_status"
        private const val KEY_DASH_ALBUM_ART = "dash_album_art"
        private const val KEY_DASH_ROUTE_EXTRAS = "dash_route_extras"
        private const val KEY_DASH_WEATHER = "dash_weather"
        private const val KEY_WEB_VIEW = "web_view"
        private const val KEY_WEB_TOUCH = "web_touch"
        private const val KEY_WEB_AUDIO = "web_audio"
        private const val KEY_GEMINI = "gemini"
        private const val OLD_KEY_AA_SLOW_PACE = "aa_slow_pace"
        private const val OLD_KEY_AA_SLOW_PACE_FPS = "aa_slow_pace_fps"
        private const val KEY_AA_NEW_PROTOCOL = "aa_new_protocol"
        private const val KEY_JOYSTICK = "joystick"
        private const val KEY_JOYSTICK_ONE_TAP = "joystick_one_tap"
        private const val OLD_KEY_CLUSTER = "cluster"
        private const val KEY_AA_SERVER_AUTO = "aa_server_auto"
        private const val KEY_HIDE_RING_MEDIA = "hide_ring_media"
        private const val KEY_RING_ALWAYS_OFF = "ring_always_off"
        private const val KEY_CLEAR_AA_FOCUS = "clear_aa_focus"

        fun open(context: Context): SettingsStore = SettingsStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
