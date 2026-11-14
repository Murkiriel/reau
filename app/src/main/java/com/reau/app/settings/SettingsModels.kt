package com.reau.app.settings

import com.reau.app.video.FrameFit

/**
 * How Android Auto's picture is fitted into the dash's dome. ("Fill the dome", the largest picture the dome's box holds, was
 * taken out on 25/10/2026; a phone that saved it reads [WHOLE_VISIBLE], the nearest, see [SettingsStore].)
 */
enum class Fit {
    /** Smaller, so that none of it is hidden by the round edge. */
    WHOLE_VISIBLE,

    /** A part of the centre, [REauSettings.cropPercent] of the content, as large as the dome allows. */
    CROP,
}

/** Where Android Auto's sound goes while the browser switch is on: the phone only, the browser only, or both. */
enum class WebAudioMode { PHONE, BROWSER, BOTH }

/** Waze's map theme, through Android Auto's night sensor (service/DayNight.kt). */
enum class MapTheme {
    /** By the sun where the phone is. */
    AUTO,

    /** Always the day theme. */
    DAY,

    /** Always the night theme. */
    NIGHT,

}

/**
 * The values of a fit (the apps' are [REauSettings]' own fields; the app menu and the navigation may have their own). The
 * crop's place is the window's top left pixel in Android Auto's picture ([cropX], [cropY]); null, centred.
 */
data class FitParams(
    val fit: Fit,
    val cropPercent: Int,
    val cropX: Int?,
    val cropY: Int?,
    val moveX: Int,
    val moveY: Int,
    /** The picture nearer or farther, in % (100: as the fit gives it; [FrameFit.ZOOM]). */
    val zoom: Int = 100,
    /** Black bands over the dash's frame, in dash pixels, to hide what the rider does not want to see. */
    val cover: FrameFit.Cover = FrameFit.Cover.NONE,
)
