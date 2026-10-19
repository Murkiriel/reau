package com.reau.app.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The language the screens are written in now: the app's own choice (Settings → Language), which before Android 13 can
 * differ from the phone's. Dates, decimal marks and day names follow it, so a screen is never half in each language.
 */
@Composable
@ReadOnlyComposable
internal fun displayLocale(): Locale = LocalConfiguration.current.locales[0]

/** [ms] as day and month, then the 24-hour time (with the seconds when [seconds]), in the order [locale] writes them. */
internal fun dayAndTime(ms: Long, locale: Locale, seconds: Boolean = false): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, if (seconds) "ddMMHHmmss" else "ddMMHHmm")
    return SimpleDateFormat(pattern, locale).format(Date(ms))
}
