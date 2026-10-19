@file:Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
package com.reau.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.dash.DashReadings
import com.reau.app.service.SunPlace
import com.reau.app.settings.SettingsStore
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow
import com.reau.app.ui.components.ValueRow
import com.reau.app.ui.components.WeatherGlyph
import com.reau.app.ui.theme.REauMono
import com.reau.app.weather.AirQuality
import com.reau.app.weather.AirQualitySeverity
import com.reau.app.weather.CurrentWeather
import com.reau.app.weather.DailyWeather
import com.reau.app.weather.HourlyWeather
import com.reau.app.weather.MoonPhase
import com.reau.app.weather.MoonPhaseCalculator
import com.reau.app.weather.UvSeverity
import com.reau.app.weather.WeatherCondition
import com.reau.app.weather.WeatherKeeper
import com.reau.app.weather.WeatherUnits
import com.reau.app.weather.windPointIndex
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle

/**
 * The weather on a screen of its own (not loose on the main screen), from a shortcut of the main screen: the switch
 * "Clima no painel", and, with it on: now, the next 8 hours and the next 8 days (one under another, with the coloured
 * icons), the 14-day chart, the wind, the UV index, the air quality, and the sun and the moon. Off, nothing of it
 * shows, and nothing is fetched or kept.
 *
 * [enabled] is the switch as the settings say it; [onToggle] saves it; [onBack] leaves the screen. The keeper fetches when the
 * screen is shown with the switch on (and when REau is opened, and while the projection runs).
 */
@Composable
fun WeatherScreen(enabled: Boolean, onToggle: (Boolean) -> Unit, onBack: () -> Unit) {
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.tile_weather), onBack, titleTag = "weather_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WeatherContent(enabled, onToggle)
            }
        }
    }
}

@Composable
private fun WeatherContent(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val kept by WeatherKeeper.snapshot.collectAsState()
    val why by WeatherKeeper.why.collectAsState()
    // The units the dash itself says it uses (03 03: Celsius or Fahrenheit; 03 04: kilometres or miles).
    val dashSettings by DashReadings.settings.collectAsState()
    val u = WeatherUnits.of(dashSettings)
    // Showing the screen, or switching the weather on, looks at once (the keeper waits between fetches by itself).
    LaunchedEffect(enabled) {
        if (enabled) runCatching { WeatherKeeper.refreshIfStale(context) }
    }
    SettingsGroup(null, null, buildList<@Composable () -> Unit> {
        add {
            SwitchRow(
                stringResource(R.string.weather_title), enabled,
                {
                    // Saved first, so an answer still on its way sees the switch off and keeps nothing; then what was kept goes.
                    onToggle(it)
                    if (!it) WeatherKeeper.clear(context)
                },
                "dash_weather", sub = stringResource(R.string.weather_sub),
                info = Info(stringResource(R.string.weather_title), stringResource(R.string.weather_info)),
            )
        }
        if (enabled) {
            val place = SunPlace.saved(context, SettingsStore.open(context).load().useLocation)
            val locale = displayLocale()
            val state = when {
                place == null -> stringResource(R.string.weather_state_no_place)
                why == WeatherKeeper.Why.NO_NETWORK -> stringResource(R.string.weather_state_no_network)
                why == WeatherKeeper.Why.FAILED -> stringResource(R.string.weather_state_failed)
                kept == null -> stringResource(R.string.weather_state_waiting)
                else -> stringResource(R.string.weather_state_ok, dayAndTime(kept!!.fetchedAtMs, locale))
            }
            add { ValueRow(stringResource(R.string.weather_state_title), state, "weather_state", mono = false) }
            val sent = WeatherKeeper.forDash()
            add {
                ValueRow(
                    stringResource(R.string.weather_sent_title),
                    if (sent == null) stringResource(R.string.weather_sent_none)
                    else stringResource(R.string.weather_sent, sent.tempC, iconName(sent.icon)),
                    "weather_sent", mono = false,
                )
            }
        }
    }, tag = "group_weather")
    val snapshot = kept
    val now = snapshot?.forecast?.current
    if (enabled && snapshot != null && now != null) {
        NowGroup(now, u)
        HoursGroup(snapshot.forecast.hourly, u)
        DaysGroup(snapshot.forecast.daily, u)
        ChartGroup(snapshot.forecast.daily, u)
        WindGroup(now, u)
        UvGroup(snapshot.forecast.daily.firstOrNull()?.uvIndexMax)
        snapshot.air?.let { AirGroup(it) }
        SunMoonGroup(snapshot.forecast.daily.firstOrNull())
        OpenMeteoCredit()
    }
}

/** CC BY 4.0 asks for the credit where the data shows (REau opens no browser, so the address is written out). */
@Composable
private fun OpenMeteoCredit() {
    Text(
        stringResource(R.string.weather_credit), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("weather_credit"),
    )
}

private const val DAYS_SHOWN = 8
private const val HOURS_SHOWN = 8

@Composable
private fun iconName(icon: Int): String = stringResource(
    when (icon) {
        1 -> R.string.weather_icon_cloudy
        2 -> R.string.weather_icon_thunder
        3 -> R.string.weather_icon_rain
        4 -> R.string.weather_icon_snow
        5 -> R.string.weather_icon_sun
        else -> R.string.weather_icon_none
    },
)

/** The name of a WMO code's condition, in the language of the screens. */
@Composable
internal fun conditionName(code: Int?): String = stringResource(
    when (WeatherCondition.forCode(code)) {
        WeatherCondition.CLEAR -> R.string.weather_condition_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.weather_condition_partly_cloudy
        WeatherCondition.CLOUDY -> R.string.weather_condition_cloudy
        WeatherCondition.FOG -> R.string.weather_condition_fog
        WeatherCondition.DRIZZLE -> R.string.weather_condition_drizzle
        WeatherCondition.RAIN -> R.string.weather_condition_rain
        WeatherCondition.SNOW -> R.string.weather_condition_snow
        WeatherCondition.THUNDERSTORM -> R.string.weather_condition_thunderstorm
        WeatherCondition.UNKNOWN -> R.string.weather_condition_unknown
    },
)

/** The header: the icon, the big temperature, the condition and the feel, then the rest of the current reading. (When it was read is in "Situação" above.) */
@Composable
private fun NowGroup(now: CurrentWeather, u: WeatherUnits) {
    val locale = displayLocale()
    val none = stringResource(R.string.bike_not_said)
    val condition = WeatherCondition.forCode(now.weatherCode)
    val name = conditionName(now.weatherCode)
    SettingsGroup(null, stringResource(R.string.weather_now), listOf(
        {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}.testTag("weather_now"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WeatherGlyph(condition, name, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        u.temp(now.temperatureC),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    now.apparentTemperatureC?.let {
                        Text(
                            stringResource(R.string.weather_feels, u.temp(it)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        { ValueRow(stringResource(R.string.weather_humidity), now.relativeHumidityPercent?.let { "$it %" } ?: none, "weather_humidity", mono = false) },
        { ValueRow(stringResource(R.string.weather_pressure), now.pressureHpa?.let { "${it.toInt()} hPa" } ?: none, "weather_pressure", mono = false) },
        { ValueRow(stringResource(R.string.weather_clouds), now.cloudCoverPercent?.let { "$it %" } ?: none, "weather_clouds", mono = false) },
        {
            ValueRow(
                stringResource(R.string.weather_rain_now), now.precipitationMm?.let { String.format(locale, "%.1f mm", it) } ?: none,
                "weather_rain_now", mono = false,
            )
        },
    ), tag = "group_weather_now")
}

/** One line of the hours and of the days: [label] at the left, the icon, the chance of rain, the temperature(s). */
@Composable
private fun ForecastLine(label: String, code: Int?, rain: Int?, first: String, second: String?, tag: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        WeatherGlyph(WeatherCondition.forCode(code), conditionName(code), 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            rain?.let { stringResource(R.string.weather_rain_chance, it) } ?: "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 56.dp),
        )
        Text(
            first,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(64.dp),
        )
        if (second != null) {
            Text(
                second,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

/** The next [HOURS_SHOWN] hours from this one on, one under another (the times are the place's own). */
@Composable
private fun HoursGroup(hourly: List<HourlyWeather>, u: WeatherUnits) {
    val from = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
    val hours = hourly.filter { runCatching { !LocalDateTime.parse(it.timeIso).isBefore(from) }.getOrDefault(false) }.take(HOURS_SHOWN)
    if (hours.isEmpty()) return
    SettingsGroup(null, stringResource(R.string.weather_hours), hours.mapIndexed { i, h ->
        val row: @Composable () -> Unit = {
            ForecastLine(
                h.timeIso.substringAfter('T').take(5), h.weatherCode, h.precipitationProbabilityPercent, u.temp(h.temperatureC), null,
                "weather_hour_$i",
            )
        }
        row
    }, tag = "group_weather_hours")
}

/** The next [DAYS_SHOWN] days, today first, with the high and the low. */
@Composable
private fun DaysGroup(daily: List<DailyWeather>, u: WeatherUnits) {
    val days = daily.take(DAYS_SHOWN)
    if (days.isEmpty()) return
    SettingsGroup(null, stringResource(R.string.weather_days), days.mapIndexed { i, d ->
        val row: @Composable () -> Unit = {
            val date = runCatching { LocalDate.parse(d.dateIso) }.getOrNull()
            val locale = displayLocale()
            val head = date?.let {
                val weekday = it.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { c -> c.uppercase(locale) }
                "%s %02d/%02d".format(weekday, it.dayOfMonth, it.monthValue)
            } ?: d.dateIso
            ForecastLine(head, d.weatherCode, d.precipitationProbabilityMaxPercent, u.temp(d.tempMaxC), u.temp(d.tempMinC), "weather_day_$i")
        }
        row
    }, tag = "group_weather_days")
}

/** The whole forecast's highs and lows as two lines (the 14-day chart). */
@Composable
private fun ChartGroup(daily: List<DailyWeather>, u: WeatherUnits) {
    if (daily.size < 2) return
    val high = MaterialTheme.colorScheme.primary
    val low = MaterialTheme.colorScheme.onSurfaceVariant
    SettingsGroup(null, stringResource(R.string.weather_chart, daily.size), listOf({
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("weather_chart"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The legend in the text colour (yellow text on the cream card had a bad contrast), each
            // with a little line of the colour of its curve.
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendItem(stringResource(R.string.weather_chart_max, u.temp(daily.maxOf { it.tempMaxC })), high)
                LegendItem(stringResource(R.string.weather_chart_min, u.temp(daily.minOf { it.tempMinC })), low)
            }
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val lo = daily.minOf { it.tempMinC }
                val span = (daily.maxOf { it.tempMaxC } - lo).coerceAtLeast(1.0)
                fun line(values: List<Double>): Path = Path().apply {
                    values.forEachIndexed { i, v ->
                        val x = i.toFloat() / (values.size - 1) * size.width
                        val y = (size.height - (v - lo) / span * size.height).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(line(daily.map { it.tempMaxC }), high, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                drawPath(line(daily.map { it.tempMinC }), low, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                drawLine(low.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                daily.forEachIndexed { i, d ->
                    // The day of the month (the date's last two characters), every other one when crowded.
                    if (daily.size <= 8 || i % 2 == 0) Text(d.dateIso.takeLast(2), style = REauMono.copy(fontSize = 10.sp), color = low)
                }
            }
        }
    }), tag = "group_weather_chart")
}

@Composable
private fun WindGroup(now: CurrentWeather, u: WeatherUnits) {
    val speed = now.windSpeedKmh ?: return
    SettingsGroup(null, stringResource(R.string.weather_wind), listOf({
        ValueRow(
            stringResource(R.string.weather_wind),
            u.speed(speed) + (now.windDirectionDeg?.let { " ${stringArrayResource(R.array.wind_points)[windPointIndex(it)]}" } ?: ""),
            "weather_wind", mono = false,
            sub = now.windGustsKmh?.let { stringResource(R.string.weather_gusts, u.speed(it)) },
        )
    }), tag = "group_weather_wind")
}

@Composable
private fun UvGroup(uvMax: Double?) {
    val severity = UvSeverity.forIndex(uvMax)
    val name = stringResource(
        when (severity) {
            UvSeverity.LOW -> R.string.weather_uv_severity_low
            UvSeverity.MODERATE -> R.string.weather_uv_severity_moderate
            UvSeverity.HIGH -> R.string.weather_uv_severity_high
            UvSeverity.VERY_HIGH -> R.string.weather_uv_severity_very_high
            UvSeverity.EXTREME -> R.string.weather_uv_severity_extreme
            UvSeverity.UNKNOWN -> R.string.weather_uv_severity_unknown
        },
    )
    val caution = when (severity) {
        UvSeverity.LOW -> R.string.weather_uv_caution_low
        UvSeverity.MODERATE -> R.string.weather_uv_caution_moderate
        UvSeverity.HIGH -> R.string.weather_uv_caution_high
        UvSeverity.VERY_HIGH -> R.string.weather_uv_caution_very_high
        UvSeverity.EXTREME -> R.string.weather_uv_caution_extreme
        UvSeverity.UNKNOWN -> null
    }
    SettingsGroup(null, stringResource(R.string.weather_uv), listOf({
        ValueRow(
            stringResource(R.string.weather_uv_today), listOfNotNull(uvMax?.let { "${it.toInt()}" }, name).joinToString(" "), "weather_uv", mono = false,
            sub = caution?.let { stringResource(it) },
        )
    }), tag = "group_weather_uv")
}

@Composable
private fun AirGroup(air: AirQuality) {
    val name = stringResource(
        when (AirQualitySeverity.forUsAqi(air.usAqi)) {
            AirQualitySeverity.GOOD -> R.string.weather_air_good
            AirQualitySeverity.MODERATE -> R.string.weather_air_moderate
            AirQualitySeverity.UNHEALTHY_SENSITIVE -> R.string.weather_air_unhealthy_sensitive
            AirQualitySeverity.UNHEALTHY -> R.string.weather_air_unhealthy
            AirQualitySeverity.VERY_UNHEALTHY -> R.string.weather_air_very_unhealthy
            AirQualitySeverity.HAZARDOUS -> R.string.weather_air_hazardous
            AirQualitySeverity.UNKNOWN -> R.string.weather_air_unknown
        },
    )
    val detail = listOfNotNull(
        air.pm10?.let { "PM10 ${Math.round(it)}" },
        air.pm25?.let { stringResource(R.string.weather_air_pm25, Math.round(it)) },
        air.ozoneUgM3?.let { "O₃ ${Math.round(it)}" },
    ).joinToString(" · ").ifEmpty { null }
    SettingsGroup(null, stringResource(R.string.weather_air), listOf({
        ValueRow(
            stringResource(R.string.weather_air_index), listOfNotNull(air.usAqi?.toString(), name).joinToString(" "), "weather_air", mono = false,
            sub = detail,
        )
    }), tag = "group_weather_air")
}

@Composable
private fun SunMoonGroup(today: DailyWeather?) {
    val moon = stringResource(
        when (MoonPhaseCalculator.phaseFor(System.currentTimeMillis())) {
            MoonPhase.NEW -> R.string.weather_moon_new
            MoonPhase.WAXING_CRESCENT -> R.string.weather_moon_waxing_crescent
            MoonPhase.FIRST_QUARTER -> R.string.weather_moon_first_quarter
            MoonPhase.WAXING_GIBBOUS -> R.string.weather_moon_waxing_gibbous
            MoonPhase.FULL -> R.string.weather_moon_full
            MoonPhase.WANING_GIBBOUS -> R.string.weather_moon_waning_gibbous
            MoonPhase.LAST_QUARTER -> R.string.weather_moon_last_quarter
            MoonPhase.WANING_CRESCENT -> R.string.weather_moon_waning_crescent
        },
    )
    val rows = buildList<@Composable () -> Unit> {
        today?.sunriseIso?.substringAfter('T')?.take(5)?.takeIf { it.isNotEmpty() }?.let { t ->
            add { ValueRow(stringResource(R.string.weather_sunrise), t, "weather_sunrise") }
        }
        today?.sunsetIso?.substringAfter('T')?.take(5)?.takeIf { it.isNotEmpty() }?.let { t ->
            add { ValueRow(stringResource(R.string.weather_sunset), t, "weather_sunset") }
        }
        add { ValueRow(stringResource(R.string.weather_moon), moon, "weather_moon", mono = false) }
    }
    SettingsGroup(null, stringResource(R.string.weather_sun_moon), rows, tag = "group_weather_sun_moon")
}

/** One item of the chart's legend: a short line of the curve's [colour], then [text] in the screen's own text colour. */
@Composable
private fun LegendItem(text: String, colour: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.width(18.dp).height(4.dp)) { drawRoundRect(colour, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
