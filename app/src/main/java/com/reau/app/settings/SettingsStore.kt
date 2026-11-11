package com.reau.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.reau.app.video.FrameFit
import com.reau.app.video.RectPx

/** The preferences of one own fit: its switch, mode, crop size, crop place (x, y), move (x, y), zoom and the four cover bands. */
@Suppress("LongParameterList") // the twelve preferences of one fit, named once
private class OwnFitKeys(
    val on: String, val fit: String, val crop: String, val x: String, val y: String, val moveX: String, val moveY: String,
    val zoom: String, val coverTop: String, val coverBottom: String, val coverLeft: String, val coverRight: String,
)

/** Keeps [REauSettings] in the app's preferences. What is saved and what is loaded is always sanitized. */
@Suppress("TooManyFunctions") // load, save and the small once-only conservative-video step; splitting them would only scatter the keys
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
            .copy(
                menuFit = kept.takeIf { loaded.menuFit != null }, menuFitKept = kept,
                navFit = loaded.navFit.placedOwn(content, NAV_KEYS, defaults.navFit),
            )
            .sanitized()
    }

    /**
     * A phone whose settings were saved by a version before the conservative video gets the conservative
     * video once (the defaults of [REauSettings]), with the newer protocol on;
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

    /** An own fit's place from its keys; with the fit never saved, the default's. */
    private fun FitParams.placedOwn(content: RectPx, keys: OwnFitKeys, default: FitParams): FitParams =
        placed(content, keys.x, keys.y, NO_KEY, NO_KEY, default.takeUnless { prefs.contains(keys.fit) })

    /** With nothing stored, the default place (AppDefaultsTest), not the centre. */
    private fun place(key: String, old: String, slack: Int, default: Int?): Int? = when {
        prefs.contains(key) -> prefs.getInt(key, 0)
        prefs.contains(old) -> slack * prefs.getInt(old, 0) / PERCENT
        else -> default
    }

    /** The navigation apps' own fit as stored (the place is read by [placed]); with nothing stored, [default]. */
    private fun ownFit(keys: OwnFitKeys, default: FitParams) = FitParams(
        fitOr(prefs.getString(keys.fit, null), default.fit), prefs.getInt(keys.crop, default.cropPercent), null, null,
        prefs.getInt(keys.moveX, default.moveX), prefs.getInt(keys.moveY, default.moveY),
        prefs.getInt(keys.zoom, default.zoom), coverOf(keys, default.cover),
    )

    private fun coverOf(keys: OwnFitKeys, default: FrameFit.Cover) = FrameFit.Cover(
        prefs.getInt(keys.coverTop, default.top), prefs.getInt(keys.coverBottom, default.bottom),
        prefs.getInt(keys.coverLeft, default.left), prefs.getInt(keys.coverRight, default.right),
    )

    private fun SharedPreferences.Editor.putZoomCover(zoom: Int, cover: FrameFit.Cover, keys: OwnFitKeys) {
        putInt(keys.zoom, zoom)
        putInt(keys.coverTop, cover.top)
        putInt(keys.coverBottom, cover.bottom)
        putInt(keys.coverLeft, cover.left)
        putInt(keys.coverRight, cover.right)
    }

    private fun SharedPreferences.Editor.putOwnFit(p: FitParams, keys: OwnFitKeys) {
        remove(keys.on)      // the switch of before: the navigation's own fit is the only one now
        putString(keys.fit, p.fit.name)
        putInt(keys.crop, p.cropPercent)
        putPlace(keys.x, p.cropX)
        putPlace(keys.y, p.cropY)
        putInt(keys.moveX, p.moveX)
        putInt(keys.moveY, p.moveY)
        putZoomCover(p.zoom, p.cover, keys)
    }

    /**
     * The app menu's own fit as stored; with nothing stored, [default] when [on]. With [on] null the switch is
     * not asked: the fit as it was kept, on or off.
     */
    private fun menuFitOr(default: FitParams, on: Boolean?): FitParams? =
        if (on != null && !prefs.getBoolean(KEY_MENU_FIT_ON, on)) null else FitParams(
            fitOr(prefs.getString(KEY_MENU_FIT, null), default.fit), prefs.getInt(KEY_MENU_CROP, default.cropPercent),
            null, null,
            prefs.getInt(KEY_MENU_MOVE_X, default.moveX), prefs.getInt(KEY_MENU_MOVE_Y, default.moveY),
            prefs.getInt(MENU_KEYS.zoom, default.zoom), coverOf(MENU_KEYS, default.cover),
        )



    @Suppress("LongMethod") // one line per setting, in the order of the class
    private fun loadAllButThePlaces(): REauSettings {
        val defaults = REauSettings()
        return REauSettings(
            density = prefs.getInt(KEY_DENSITY, defaults.density),
            marginW = prefs.getInt(KEY_MARGIN_W, defaults.marginW),
            marginH = prefs.getInt(KEY_MARGIN_H, defaults.marginH),
            fit = fitOr(prefs.getString(KEY_FIT, null), defaults.fit),
            cropPercent = prefs.getInt(KEY_CROP, defaults.cropPercent),
            moveX = prefs.getInt(KEY_MOVE_X, defaults.moveX),
            moveY = prefs.getInt(KEY_MOVE_Y, defaults.moveY),
            zoom = prefs.getInt(APPS_KEYS.zoom, defaults.zoom),
            cover = coverOf(APPS_KEYS, defaults.cover),
            menuFit = menuFitOr(defaults.menuFit ?: defaults.appsFit, defaults.menuFit != null),
            menuFitKept = menuFitOr(defaults.menuFitKept, on = null) ?: defaults.menuFitKept,
            navFit = ownFit(NAV_KEYS, defaults.navFit),
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
            noDashStopMinutes = prefs.getInt(KEY_NO_DASH_STOP_MINUTES, defaults.noDashStopMinutes),
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
            clearAaFocus = prefs.getBoolean(KEY_CLEAR_AA_FOCUS, defaults.clearAaFocus),
            dashMask = prefs.getBoolean(KEY_DASH_MASK, defaults.dashMask),
            statsOverlay = prefs.getBoolean(KEY_STATS_OVERLAY, defaults.statsOverlay),
            statsX = prefs.getInt(KEY_STATS_X, defaults.statsX),
            statsY = prefs.getInt(KEY_STATS_Y, defaults.statsY),
            statsSize = prefs.getInt(KEY_STATS_SIZE, defaults.statsSize),
            buttonOverlay = prefs.getBoolean(KEY_BUTTON_OVERLAY, defaults.buttonOverlay),
            buttonX = prefs.getInt(KEY_BUTTON_X, defaults.buttonX),
            buttonY = prefs.getInt(KEY_BUTTON_Y, defaults.buttonY),
            buttonSize = prefs.getInt(KEY_BUTTON_SIZE, defaults.buttonSize),
            dashPhoneStatus = prefs.getBoolean(KEY_DASH_PHONE_STATUS, defaults.dashPhoneStatus),
            dashAlbumArt = prefs.getBoolean(KEY_DASH_ALBUM_ART, defaults.dashAlbumArt),
            maneuverRing = prefs.getBoolean(KEY_MANEUVER_RING, defaults.maneuverRing),
            dashWeather = prefs.getBoolean(KEY_DASH_WEATHER, defaults.dashWeather),
            infoRing = prefs.getBoolean(KEY_INFO_RING, defaults.infoRing),
            infoRingData = prefs.getInt(KEY_INFO_RING_DATA, defaults.infoRingData).let {
                if (it == com.reau.app.weather.RingData.BEFORE_PHONE_CARDS) com.reau.app.weather.RingData.ALL else it
            },
            infoRingCarousel = prefs.getBoolean(KEY_INFO_RING_CAROUSEL, defaults.infoRingCarousel),
            infoRingSeconds = prefs.getInt(KEY_INFO_RING_SECONDS, defaults.infoRingSeconds),
            infoRingInPlace = prefs.getBoolean(KEY_INFO_RING_IN_PLACE, defaults.infoRingInPlace),
            webView = prefs.getBoolean(KEY_WEB_VIEW, defaults.webView),
            webTouch = prefs.getBoolean(KEY_WEB_TOUCH, defaults.webTouch),
            webAudio = WebAudioMode.entries.firstOrNull { it.name == prefs.getString(KEY_WEB_AUDIO, null) } ?: defaults.webAudio,
            experimentRtpPacing = prefs.getBoolean(KEY_EXPERIMENT_RTP_PACING, defaults.experimentRtpPacing),
            experimentDebugDscp = prefs.getBoolean(KEY_EXPERIMENT_DEBUG_DSCP, defaults.experimentDebugDscp),
            experimentAdaptiveBitrate = prefs.getBoolean(KEY_EXPERIMENT_ADAPTIVE_BITRATE, defaults.experimentAdaptiveBitrate),
            experimentWifiBind = prefs.getBoolean(KEY_EXPERIMENT_WIFI_BIND, defaults.experimentWifiBind),
            experimentWifiBand = prefs.getBoolean(KEY_EXPERIMENT_WIFI_BAND, defaults.experimentWifiBand),
            experimentStartGuard = prefs.getBoolean(KEY_EXPERIMENT_START_GUARD, defaults.experimentStartGuard),
            experimentEncoderWatch = prefs.getBoolean(KEY_EXPERIMENT_ENCODER_WATCH, defaults.experimentEncoderWatch),
            experimentFocusGap = prefs.getBoolean(KEY_EXPERIMENT_FOCUS_GAP, defaults.experimentFocusGap),
            experimentNightMemory = prefs.getBoolean(KEY_EXPERIMENT_NIGHT_MEMORY, defaults.experimentNightMemory),
            experimentWeatherAge = prefs.getBoolean(KEY_EXPERIMENT_WEATHER_AGE, defaults.experimentWeatherAge),
            experimentHeatCeiling = prefs.getBoolean(KEY_EXPERIMENT_HEAT_CEILING, defaults.experimentHeatCeiling),
            experimentGpsSpeed = prefs.getBoolean(KEY_EXPERIMENT_GPS_SPEED, defaults.experimentGpsSpeed),
            experimentRecalcInactive = prefs.getBoolean(KEY_EXPERIMENT_RECALC_INACTIVE, defaults.experimentRecalcInactive),
            experimentColorMatrix = prefs.getBoolean(KEY_EXPERIMENT_COLOR_MATRIX, defaults.experimentColorMatrix),
            experimentProtocolSix = prefs.getBoolean(KEY_EXPERIMENT_PROTOCOL_SIX, defaults.experimentProtocolSix),
            experimentTolerantRx = prefs.getBoolean(KEY_EXPERIMENT_TOLERANT_RX, defaults.experimentTolerantRx),
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
            putZoomCover(s.zoom, s.cover, APPS_KEYS)
            putBoolean(KEY_MENU_FIT_ON, s.menuFit != null)
            s.menuFitKept.let { m ->
                putString(KEY_MENU_FIT, m.fit.name)
                putInt(KEY_MENU_CROP, m.cropPercent)
                putPlace(KEY_MENU_CROP_X, m.cropX)
                putPlace(KEY_MENU_CROP_Y, m.cropY)
                putInt(KEY_MENU_MOVE_X, m.moveX)
                putInt(KEY_MENU_MOVE_Y, m.moveY)
                putZoomCover(m.zoom, m.cover, MENU_KEYS)
            }
            putOwnFit(s.navFit, NAV_KEYS)
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
            putInt(KEY_NO_DASH_STOP_MINUTES, s.noDashStopMinutes)
            putBoolean(KEY_ANSWER_CALLS, s.answerCalls)
            putBoolean(KEY_PHONE_TOUCH, s.phoneTouch)
            putBoolean(KEY_DASH_MUSIC, s.dashMusic)
            putBoolean(KEY_DASH_CALLER, s.dashCaller)
            putBoolean(KEY_GEMINI, s.gemini)
            putBoolean(KEY_AA_NEW_PROTOCOL, s.aaNewProtocol)
            putString(KEY_JOYSTICK, s.joystick)
            putBoolean(KEY_JOYSTICK_ONE_TAP, true)
            // The ring-hiding switches went (hiding the ring never worked on the dash); their old keys go with them.
            remove(KEY_HIDE_RING_MEDIA)
            remove(KEY_RING_ALWAYS_OFF)
            putBoolean(KEY_CLEAR_AA_FOCUS, s.clearAaFocus)
            putBoolean(KEY_DASH_MASK, s.dashMask)
            putBoolean(KEY_STATS_OVERLAY, s.statsOverlay)
            putInt(KEY_STATS_X, s.statsX)
            putInt(KEY_STATS_Y, s.statsY)
            putInt(KEY_STATS_SIZE, s.statsSize)
            putBoolean(KEY_BUTTON_OVERLAY, s.buttonOverlay)
            putInt(KEY_BUTTON_X, s.buttonX)
            putInt(KEY_BUTTON_Y, s.buttonY)
            putInt(KEY_BUTTON_SIZE, s.buttonSize)
            putBoolean(KEY_DASH_PHONE_STATUS, s.dashPhoneStatus)
            putBoolean(KEY_DASH_ALBUM_ART, s.dashAlbumArt)
            putBoolean(KEY_MANEUVER_RING, s.maneuverRing)
            putBoolean(KEY_DASH_WEATHER, s.dashWeather)
            putBoolean(KEY_INFO_RING, s.infoRing)
            putInt(KEY_INFO_RING_DATA, s.infoRingData)
            putBoolean(KEY_INFO_RING_CAROUSEL, s.infoRingCarousel)
            putInt(KEY_INFO_RING_SECONDS, s.infoRingSeconds)
            putBoolean(KEY_INFO_RING_IN_PLACE, s.infoRingInPlace)
            putBoolean(KEY_WEB_VIEW, s.webView)
            putBoolean(KEY_WEB_TOUCH, s.webTouch)
            putString(KEY_WEB_AUDIO, s.webAudio.name)
            putBoolean(KEY_EXPERIMENT_RTP_PACING, s.experimentRtpPacing)
            putBoolean(KEY_EXPERIMENT_DEBUG_DSCP, s.experimentDebugDscp)
            putBoolean(KEY_EXPERIMENT_ADAPTIVE_BITRATE, s.experimentAdaptiveBitrate)
            putBoolean(KEY_EXPERIMENT_WIFI_BIND, s.experimentWifiBind)
            putBoolean(KEY_EXPERIMENT_WIFI_BAND, s.experimentWifiBand)
            putBoolean(KEY_EXPERIMENT_START_GUARD, s.experimentStartGuard)
            putBoolean(KEY_EXPERIMENT_ENCODER_WATCH, s.experimentEncoderWatch)
            putBoolean(KEY_EXPERIMENT_FOCUS_GAP, s.experimentFocusGap)
            putBoolean(KEY_EXPERIMENT_NIGHT_MEMORY, s.experimentNightMemory)
            putBoolean(KEY_EXPERIMENT_WEATHER_AGE, s.experimentWeatherAge)
            putBoolean(KEY_EXPERIMENT_HEAT_CEILING, s.experimentHeatCeiling)
            putBoolean(KEY_EXPERIMENT_GPS_SPEED, s.experimentGpsSpeed)
            putBoolean(KEY_EXPERIMENT_RECALC_INACTIVE, s.experimentRecalcInactive)
            putBoolean(KEY_EXPERIMENT_COLOR_MATRIX, s.experimentColorMatrix)
            putBoolean(KEY_EXPERIMENT_PROTOCOL_SIX, s.experimentProtocolSix)
            putBoolean(KEY_EXPERIMENT_TOLERANT_RX, s.experimentTolerantRx)
        }
    }

    /** A crop's place, or none when it is centred. */
    private fun SharedPreferences.Editor.putPlace(key: String, place: Int?) {
        if (place == null) remove(key) else putInt(key, place)
    }

    /** A saved fit; the "fill the dome" of before reads as the whole picture, the nearest left. */
    private fun fitOr(name: String?, default: Fit): Fit = if (name == "FILL_DOME") Fit.WHOLE_VISIBLE else enumOr(name, default)

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
        /** The preferences of the navigation apps' own fit. */
        private val NAV_KEYS = ownKeys("nav")
        private val MENU_KEYS = ownKeys("menu")

        /** The apps' own fields' preferences for the zoom and the cover. */
        private val APPS_KEYS = ownKeys("apps")

        private fun ownKeys(name: String) =
            OwnFitKeys(
                "${name}_fit_on", "${name}_fit", "${name}_crop_percent", "${name}_crop_x", "${name}_crop_y", "${name}_move_x", "${name}_move_y",
                "${name}_zoom", "${name}_cover_top", "${name}_cover_bottom", "${name}_cover_left", "${name}_cover_right",
            )

        /** No key: a fit that never had old percent keys to read (the place is stored in pixels from the start). */
        private const val NO_KEY = ""
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
        private const val KEY_NO_DASH_STOP_MINUTES = "no_dash_stop_minutes"
        private const val KEY_ANSWER_CALLS = "answer_calls"
        private const val KEY_PHONE_TOUCH = "phone_touch"
        private const val KEY_DASH_MUSIC = "dash_music"
        private const val KEY_DASH_CALLER = "dash_caller"
        private const val KEY_DASH_MASK = "dash_mask"
        private const val KEY_STATS_OVERLAY = "stats_overlay"
        private const val KEY_STATS_X = "stats_x"
        private const val KEY_STATS_Y = "stats_y"
        private const val KEY_STATS_SIZE = "stats_size"
        private const val KEY_BUTTON_OVERLAY = "button_overlay"
        private const val KEY_BUTTON_X = "button_x"
        private const val KEY_BUTTON_Y = "button_y"
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_DASH_PHONE_STATUS = "dash_phone_status"
        private const val KEY_DASH_ALBUM_ART = "dash_album_art"
        private const val KEY_MANEUVER_RING = "maneuver_ring"
        private const val KEY_DASH_WEATHER = "dash_weather"
        private const val KEY_INFO_RING = "info_ring"
        private const val KEY_INFO_RING_DATA = "info_ring_data"
        private const val KEY_INFO_RING_CAROUSEL = "info_ring_carousel"
        private const val KEY_INFO_RING_SECONDS = "info_ring_seconds"
        private const val KEY_INFO_RING_IN_PLACE = "info_ring_in_place"
        private const val KEY_WEB_VIEW = "web_view"
        private const val KEY_WEB_TOUCH = "web_touch"
        private const val KEY_WEB_AUDIO = "web_audio"
        private const val KEY_GEMINI = "gemini"
        private const val KEY_EXPERIMENT_RTP_PACING = "experiment_rtp_pacing"
        private const val KEY_EXPERIMENT_DEBUG_DSCP = "experiment_debug_dscp"
        private const val KEY_EXPERIMENT_ADAPTIVE_BITRATE = "experiment_adaptive_bitrate"
        private const val KEY_EXPERIMENT_WIFI_BIND = "experiment_wifi_bind"
        private const val KEY_EXPERIMENT_WIFI_BAND = "experiment_wifi_band"
        private const val KEY_EXPERIMENT_START_GUARD = "experiment_start_guard"
        private const val KEY_EXPERIMENT_ENCODER_WATCH = "experiment_encoder_watch"
        private const val KEY_EXPERIMENT_FOCUS_GAP = "experiment_focus_gap"
        private const val KEY_EXPERIMENT_NIGHT_MEMORY = "experiment_night_memory"
        private const val KEY_EXPERIMENT_WEATHER_AGE = "experiment_weather_age"
        private const val KEY_EXPERIMENT_HEAT_CEILING = "experiment_heat_ceiling"
        private const val KEY_EXPERIMENT_GPS_SPEED = "experiment_gps_speed"
        private const val KEY_EXPERIMENT_RECALC_INACTIVE = "experiment_recalc_inactive"
        private const val KEY_EXPERIMENT_COLOR_MATRIX = "experiment_color_matrix"
        private const val KEY_EXPERIMENT_PROTOCOL_SIX = "experiment_protocol_six"
        private const val KEY_EXPERIMENT_TOLERANT_RX = "experiment_tolerant_rx"
        private const val OLD_KEY_AA_SLOW_PACE = "aa_slow_pace"
        private const val OLD_KEY_AA_SLOW_PACE_FPS = "aa_slow_pace_fps"
        private const val KEY_AA_NEW_PROTOCOL = "aa_new_protocol"
        private const val KEY_JOYSTICK = "joystick"
        private const val KEY_JOYSTICK_ONE_TAP = "joystick_one_tap"
        private const val OLD_KEY_CLUSTER = "cluster"
        private const val KEY_HIDE_RING_MEDIA = "hide_ring_media"
        private const val KEY_RING_ALWAYS_OFF = "ring_always_off"
        private const val KEY_CLEAR_AA_FOCUS = "clear_aa_focus"

        fun open(context: Context): SettingsStore = SettingsStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
