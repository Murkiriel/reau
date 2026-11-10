package com.reau.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.ui.MainLook.Warning
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.ShortcutTile
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.WarningCard

// The main screen's warnings and shortcuts, apart from its frame in MainScreen.kt.

/** One warning above the bridge, with its way out. */
@Composable
internal fun Warning(warning: Warning, state: MainUiState, onOpenAndroidAutoSettings: () -> Unit, onOpenSettings: () -> Unit, extras: MainExtras) {
    val warn = StatusKind.CONNECTING
    when (warning) {
        Warning.SERVER_OFF -> ServerWarning(state.androidAutoInstalled, onOpenAndroidAutoSettings)
        Warning.UNRESPONSIVE -> UnresponsiveWarning()
        Warning.CLOSING_PREVIOUS -> WarningCard(
            warn, R.drawable.ic_ms_error, stringResource(R.string.warn_closing_previous_title),
            stringResource(R.string.aa_closing_previous_warning), null, null, tag = "aa_closing_previous_warning",
        )
        Warning.OTHER_EDITION -> WarningCard(
            warn, R.drawable.ic_ms_error, stringResource(R.string.warn_other_edition_title),
            stringResource(R.string.aa_other_edition_warning), null, null, tag = "aa_other_edition_warning",
        )
        Warning.NOT_CALLING -> WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_not_calling_title),
            stringResource(R.string.aa_not_calling_warning),
            null, CardAction(aaButtonLabel(state.androidAutoInstalled), "open_aa_settings", onOpenAndroidAutoSettings),
            tag = "aa_not_calling_warning",
        )
        Warning.NO_VIDEO -> WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_no_video_title),
            stringResource(R.string.aa_no_video_warning), null, null, tag = "aa_no_video_warning",
        )
        Warning.BATTERY_EXEMPTION -> WarningCard(
            warn, R.drawable.ic_ms_battery_alert, stringResource(R.string.warn_battery_exemption_title), stringResource(R.string.battery_not_exempt),
            null, CardAction(stringResource(R.string.action_battery_exemption), "ask_battery_exemption", extras.onAskBatteryExemption),
            tag = "battery_exemption",
        )
        Warning.BATTERY_SAVER -> WarningCard(
            warn, R.drawable.ic_ms_battery_alert, stringResource(R.string.warn_battery_saver_title), stringResource(R.string.battery_saver_on),
            null, CardAction(stringResource(R.string.action_battery_saver), "open_battery_saver", extras.onOpenBatterySaver),
            tag = "battery_saver",
        )
        Warning.DASH_TAKEN -> WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_taken_title), stringResource(R.string.dash_taken_warning),
            null, null, tag = "dash_taken_warning",
        )
        Warning.VPN -> WarningCard(
            warn, R.drawable.ic_ms_error, stringResource(R.string.warn_vpn_title), stringResource(R.string.warn_vpn),
            null, null, tag = "vpn_warning",
        )
        Warning.LOCATION_OFF -> WarningCard(
            warn, R.drawable.ic_ms_error, stringResource(R.string.warn_location_title), stringResource(R.string.warn_location),
            null, null, tag = "location_warning",
        )
        Warning.NAME_NEEDED -> WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_name_title), stringResource(R.string.dash_name_warning),
            null, CardAction(stringResource(R.string.action_open_settings_short), "name_open_settings", onOpenSettings),
            tag = "dash_name_warning",
        )
    }
}

/** The "Android Auto does not answer" card: how to restart its server, in Android Auto's own menu. */
@Composable
private fun UnresponsiveWarning() {
    WarningCard(
        StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_unresponsive_title),
        stringResource(R.string.aa_unresponsive_warning), null, null, tag = "aa_unresponsive_warning",
    )
}

/** While Android Auto's head unit server is off: how to switch it on by hand, with the way to Android Auto's settings. */
@Composable
private fun ServerWarning(aaInstalled: Boolean, onOpenAndroidAutoSettings: () -> Unit) {
    // Without Android Auto the card says so first: its settings button is the way to the store.
    val missing = if (aaInstalled) null else stringResource(R.string.aa_missing_note)
    val more = listOfNotNull(missing?.let { it to "aa_missing_note" })
    WarningCard(
        StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_server_title), stringResource(R.string.server_warning),
        null, CardAction(aaButtonLabel(aaInstalled), "open_aa_settings", onOpenAndroidAutoSettings), tag = "server_warning", more = more,
    )
}

/** The six shortcuts, two to a line; the Android Auto screen says why when it cannot open ([say], a Snackbar). */
@Composable
internal fun Shortcuts(state: MainUiState, onOpenAndroidAutoSettings: () -> Unit, onOpenSettings: () -> Unit, extras: MainExtras, say: (String) -> Unit) {
    val unavailable = MainLook.screenUnavailable(state.projection)?.let { stringResource(it) }
    val screenTitle = stringResource(R.string.tile_aa_screen)
    val permissions = if (state.permissionsMissing == 0) {
        stringResource(R.string.tile_permissions_all)
    } else {
        pluralStringResource(R.plurals.tile_permissions_missing, state.permissionsMissing, state.permissionsMissing)
    }
    TileRow({
        ShortcutTile(
            R.drawable.ic_ms_fit_screen, screenTitle, stringResource(R.string.tile_aa_screen_sub), extras.onShowAndroidAuto, it,
            enabled = unavailable == null, reason = unavailable, onUnavailable = { why -> say("$screenTitle: ${why.lowercase()}.") },
            tag = "show_aa_screen",
        )
    }, {
        ShortcutTile(
            R.drawable.ic_ms_two_wheeler, stringResource(R.string.tile_wifi), SsidMask.of(state.dashName).ifEmpty { stringResource(R.string.tile_wifi_none) },
            extras.onOpenWifi, it, tag = "tile_wifi",
        )
    })
    TileRow({
        ShortcutTile(
            R.drawable.ic_ms_directions_car, stringResource(R.string.tile_aa),
            stringResource(if (state.androidAutoInstalled) R.string.tile_aa_sub else R.string.tile_aa_sub_missing), onOpenAndroidAutoSettings, it,
            external = true, tag = "aa_settings_shortcut",
        )
    }, {
        ShortcutTile(
            R.drawable.ic_ms_tune, stringResource(R.string.action_settings), stringResource(R.string.tile_settings_sub), onOpenSettings, it,
            tag = "open_settings",
        )
    })
    TileRow({
        ShortcutTile(R.drawable.ic_ms_shield, stringResource(R.string.tile_permissions), permissions, extras.onOpenPermissions, it, tag = "open_permissions")
    }, {
        ShortcutTile(
            R.drawable.ic_ms_lock, stringResource(R.string.tile_data), stringResource(R.string.tile_data_sub), extras.onOpenAboutData, it,
            tag = "open_about_data",
        )
    })
    TileRow({
        ShortcutTile(
            R.drawable.ic_ms_weather, stringResource(R.string.tile_weather), weatherSub(state.weatherOn), extras.onOpenWeather, it,
            tag = "tile_weather",
        )
    }, null)
}

/** Two shortcuts side by side, of the same height. */
@Composable
private fun TileRow(left: @Composable (Modifier) -> Unit, right: (@Composable (Modifier) -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        left(Modifier.weight(1f).fillMaxHeight())
        // A last shortcut with no pair takes the whole line; with one more shortcut it is side by side again.
        right?.invoke(Modifier.weight(1f).fillMaxHeight())
    }
}

/** What the weather shortcut says under its name: off, or the temperature and the condition now, in the units the dash says. */
@Composable
private fun weatherSub(on: Boolean): String {
    if (!on) return stringResource(R.string.tile_weather_off)
    val kept by com.reau.app.weather.WeatherKeeper.snapshot.collectAsState()
    val dash by com.reau.app.dash.DashReadings.settings.collectAsState()
    val why by com.reau.app.weather.WeatherKeeper.why.collectAsState()
    // No fresh weather: say why in a few words, the same reason the weather screen gives, instead of "fetching" forever.
    val now = kept?.forecast?.current ?: return stringResource(
        when (why) {
            com.reau.app.weather.WeatherKeeper.Why.NO_PLACE -> R.string.tile_weather_no_place
            com.reau.app.weather.WeatherKeeper.Why.NO_NETWORK -> R.string.tile_weather_no_network
            com.reau.app.weather.WeatherKeeper.Why.FAILED -> R.string.tile_weather_failed
            com.reau.app.weather.WeatherKeeper.Why.NONE -> R.string.tile_weather_waiting
        },
    )
    return "${com.reau.app.weather.WeatherUnits.of(dash).temp(now.temperatureC)} · ${conditionName(now.weatherCode)}"
}

/** The label of the Android Auto button: its settings when it is installed, else the way to get it. */
@Composable
private fun aaButtonLabel(installed: Boolean): String =
    stringResource(if (installed) R.string.action_open_aa_settings else R.string.action_get_aa)
