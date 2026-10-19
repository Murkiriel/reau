package com.reau.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.dash.DashReadings
import com.reau.app.dash.DashReading
import com.reau.app.dash.DashReport
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.RowAction
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.ValueRow

/**
 * What the dash says, on the screen "Dados da moto": everything the dash reports goes to this screen. It only tells:
 * the dash's settings are shown, never written (read only, not even a test write). What never came, or nobody knows
 * what it is, is not on the screen: the identity codes (VIN, serial, the dash's Wi-Fi address), which REau neither
 * shows nor keeps, and the unnamed codes. The time is only on the first line, "Última leitura" (no date and time on each item).
 */
@Composable
fun BikeDataGroups() {
    val all by DashReadings.all.collectAsState()
    LastReadGroup(all)
    RangeGroup(all)
    VersionGroup(all)
    SettingsReadGroup(all)
    StateGroup(all)
}

@Composable
private fun LastReadGroup(all: Map<String, DashReading>) {
    val last = all.values.maxOfOrNull { it.atMs }
    SettingsGroup(R.drawable.ic_ms_two_wheeler, null, listOf(
        {
            ValueRow(
                stringResource(R.string.bike_last_read_title),
                if (last == null) stringResource(R.string.bike_never) else dayAndTime(last, displayLocale(), seconds = true),
                "bike_last_read", mono = last != null,
                info = Info(stringResource(R.string.bike_last_read_title), stringResource(R.string.bike_last_read_info)),
            )
        },
    ), tag = "group_bike_last")
}

/**
 * The range (`0C07`), in the unit the dash is set to, and when the dash last said it. With no reading it says so ("Ainda
 * não disse") and has no time line: a number is never made up.
 */
@Composable
private fun RangeGroup(all: Map<String, DashReading>) {
    val reading = all["0C07"]
    val km = DashReport.rangeKm(reading?.hex)
    val unit = DashReport.settings(all).distance
    val title = stringResource(R.string.bike_range)
    SettingsGroup(null, stringResource(R.string.bike_group_range), listOfNotNull<@Composable () -> Unit>(
        {
            ValueRow(
                title, if (km == null) stringResource(R.string.bike_not_said) else DashReport.rangeText(km, unit), "bike_range",
                mono = km != null, info = Info(title, stringResource(R.string.bike_range_info)),
            )
        },
        if (km == null || reading == null) {
            null
        } else {
            { ValueRow(stringResource(R.string.bike_range_at), dayAndTime(reading.atMs, displayLocale(), seconds = true), "bike_range_at") }
        },
    ), tag = "group_bike_range")
}

/** The firmware (the second partition of `0B02`, the version a rider knows), the whole list, the variant, the safe mode. */
@Composable
private fun VersionGroup(all: Map<String, DashReading>) {
    val parts = DashReport.partitions(all["0B02"]?.hex)
    val said = stringResource(R.string.bike_not_said)
    val safe = DashReport.safeMode(all["0F04"]?.hex)
    SettingsGroup(null, stringResource(R.string.bike_group_version), listOf(
        {
            ValueRow(
                stringResource(R.string.bike_firmware), parts.getOrNull(1)?.version ?: said, "bike_firmware",
            )
        },
        {
            ValueRow(
                stringResource(R.string.bike_parts), if (parts.isEmpty()) said else parts.joinToString("\n") { "%02d  %s".format(it.block, it.version) },
                "bike_parts",
            )
        },
        { ValueRow(stringResource(R.string.bike_variant), DashReport.text(all["0F0B"]?.hex) ?: said, "bike_variant") },
        {
            ValueRow(
                stringResource(R.string.bike_safe_mode),
                when (safe) {
                    true -> stringResource(R.string.bike_safe_mode_on)
                    false -> stringResource(R.string.bike_safe_mode_off)
                    null -> said
                },
                "bike_safe_mode", mono = false,
            )
        },
    ), tag = "group_bike_version")
}

/** The dash's own settings, in words (units, theme, clock, language); the notification and place masks are hidden. */
@Composable
private fun SettingsReadGroup(all: Map<String, DashReading>) {
    val set = DashReport.settings(all)
    val said = stringResource(R.string.bike_not_said)

    val tempText = when (set.temperature) {
        DashReport.TempUnit.CELSIUS -> stringResource(R.string.bike_celsius)
        DashReport.TempUnit.FAHRENHEIT -> stringResource(R.string.bike_fahrenheit)
        null -> said
    }
    val distanceText = when (set.distance) {
        DashReport.DistanceUnit.KM -> stringResource(R.string.bike_km)
        DashReport.DistanceUnit.MILES -> stringResource(R.string.bike_miles)
        null -> said
    }
    val themeText = when (set.theme) {
        DashReport.Theme.AUTO -> stringResource(R.string.bike_theme_auto)
        DashReport.Theme.DARK -> stringResource(R.string.bike_theme_dark)
        null -> said
    }
    val consumptionText = when (set.consumption) {
        DashReport.ConsumptionUnit.KM_PER_L -> stringResource(R.string.bike_kml)
        DashReport.ConsumptionUnit.L_PER_100KM -> stringResource(R.string.bike_l100)
        DashReport.ConsumptionUnit.MPG_UK -> stringResource(R.string.bike_mpg_uk)
        DashReport.ConsumptionUnit.MPG_US -> stringResource(R.string.bike_mpg_us)
        null -> said
    }
    val clockText = when (set.clock) {
        DashReport.ClockFormat.H24 -> stringResource(R.string.bike_h24)
        DashReport.ClockFormat.H12 -> stringResource(R.string.bike_h12)
        null -> said
    }
    val languageText = when (set.language) {
        DashReport.Language.EN -> stringResource(R.string.bike_lang_en)
        DashReport.Language.IT -> stringResource(R.string.bike_lang_it)
        DashReport.Language.ES -> stringResource(R.string.bike_lang_es)
        DashReport.Language.PT -> stringResource(R.string.bike_lang_pt)
        DashReport.Language.DE -> stringResource(R.string.bike_lang_de)
        DashReport.Language.FR -> stringResource(R.string.bike_lang_fr)
        null -> said
    }
    SettingsGroup(null, stringResource(R.string.bike_group_settings), listOf(
        { ValueRow(stringResource(R.string.bike_set_temperature), tempText, "bike_set_temperature", mono = false) },
        { ValueRow(stringResource(R.string.bike_set_distance), distanceText, "bike_set_distance", mono = false) },
        { ValueRow(stringResource(R.string.bike_set_theme), themeText, "bike_set_theme", mono = false) },
        { ValueRow(stringResource(R.string.bike_set_consumption), consumptionText, "bike_set_consumption", mono = false) },
        { ValueRow(stringResource(R.string.bike_set_clock), clockText, "bike_set_clock", mono = false) },
        { ValueRow(stringResource(R.string.bike_set_language), languageText, "bike_set_language", mono = false) },
    ), tag = "group_bike_settings")
}

/** What the bike says by group 0C when something changes: the ABS and the low-fuel reminder (the only two known), on or off, and when. */
@Composable
private fun StateGroup(all: Map<String, DashReading>) {
    val abs = DashReport.abs(all["0C14"]?.hex)
    val fuel = DashReport.lowFuel(all["0C05"]?.hex)
    val said = stringResource(R.string.bike_not_said)
    SettingsGroup(null, stringResource(R.string.bike_group_state), listOf(
        {
            ValueRow(
                stringResource(R.string.bike_abs),
                abs?.let { stringResource(if (it.on) R.string.bike_fuel_on else R.string.bike_fuel_off) } ?: said,
                "bike_abs", mono = false,
            )
        },
        {
            ValueRow(
                stringResource(R.string.bike_fuel),
                fuel?.let { stringResource(if (it) R.string.bike_fuel_on else R.string.bike_fuel_off) } ?: said,
                "bike_fuel", mono = false,
            )
        },
    ), tag = "group_bike_state")
}
