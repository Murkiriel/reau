package com.reau.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reau.app.OtherEdition
import com.reau.app.R
import com.reau.app.aa.ServerResult
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.ui.MainLook.ServerAction
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
        Warning.SERVER_OFF -> ServerWarning(state.server, state.androidAutoInstalled, onOpenAndroidAutoSettings, extras)
        Warning.UNRESPONSIVE -> UnresponsiveWarning(state, onOpenAndroidAutoSettings, extras)
        Warning.CLOSING_PREVIOUS -> WarningCard(
            warn, R.drawable.ic_ms_error, stringResource(R.string.warn_closing_previous_title),
            stringResource(R.string.aa_closing_previous_warning), null, null, tag = "aa_closing_previous_warning",
        )
        Warning.OTHER_EDITION -> WarningCard(
            warn, R.drawable.ic_ms_error, stringResource(R.string.warn_other_edition_title),
            stringResource(R.string.aa_other_edition_warning, OtherEdition.nameOf()), null, null, tag = "aa_other_edition_warning",
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
        Warning.NAME_NEEDED -> WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_name_title), stringResource(R.string.dash_name_warning),
            null, CardAction(stringResource(R.string.action_open_settings_short), "name_open_settings", onOpenSettings),
            tag = "dash_name_warning",
        )
    }
}

/** The "Android Auto does not answer" card: how to restart its server, with the way to the accessibility settings when the service is off. */
@Composable
private fun UnresponsiveWarning(state: MainUiState, onOpenAndroidAutoSettings: () -> Unit, extras: MainExtras) {
    // Where REau cannot restart the server itself, the rider is told how to do it in Android Auto's own menu.
    val byHand = MainLook.muteByRider(state.server)
    // The full edition with the service off still has the way out of asking for it: its line, as under the server card.
    val accessibility = MainLook.serverLines(state.server).accessibility?.let { stringResource(it) }
    val aaSettings = CardAction(aaButtonLabel(state.androidAutoInstalled), "open_aa_settings", onOpenAndroidAutoSettings)
    AccessibilityAsk(extras.onOpenAccessibility) { askAccessibility ->
        // The line speaks of the accessibility service, so the way to it is on the card, as under the server card.
        val primary = if (MainLook.serverAction(state.server) == ServerAction.OPEN_ACCESSIBILITY) {
            CardAction(stringResource(R.string.action_open_accessibility), "open_accessibility", askAccessibility)
        } else {
            null
        }
        WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_unresponsive_title),
            stringResource(if (byHand) R.string.aa_unresponsive_by_hand_warning else R.string.aa_unresponsive_warning),
            primary, if (primary != null) aaSettings else null,
            tag = "aa_unresponsive_warning", more = listOfNotNull(accessibility?.let { it to "aa_server_accessibility" }),
        )
    }
}

/**
 * While Android Auto's head unit server is off: how to switch it on, with the way to Android Auto's settings and, with
 * the automatic switch, how its try went and "Tentar de novo" or the way to the accessibility settings.
 */
@Composable
private fun ServerWarning(server: ServerUi, aaInstalled: Boolean, onOpenAndroidAutoSettings: () -> Unit, extras: MainExtras) {
    // The Lite edition has no accessibility service: its warning never speaks of the automatic switch.
    val lines = MainLook.serverLines(server)
    val status = lines.status?.let { stringResource(it) }
    val accessibility = lines.accessibility?.let { stringResource(it) }
    // Without Android Auto the card says so first: its settings button is the way to the store.
    val missing = if (aaInstalled) null else stringResource(R.string.aa_missing_note)
    val more = listOfNotNull(
        missing?.let { it to "aa_missing_note" }, status?.let { it to "aa_server_status" }, accessibility?.let { it to "aa_server_accessibility" },
    )
    val retry = CardAction(stringResource(R.string.action_retry_aa_server), "retry_aa_server", extras.onRetryServer)
    val aaSettings = CardAction(aaButtonLabel(aaInstalled), "open_aa_settings", onOpenAndroidAutoSettings)
    AccessibilityAsk(extras.onOpenAccessibility) { askAccessibility ->
        val (primary, secondary) = when (MainLook.serverAction(server)) {
            ServerAction.RETRY -> retry to aaSettings
            ServerAction.OPEN_ACCESSIBILITY ->
                CardAction(stringResource(R.string.action_open_accessibility), "open_accessibility", askAccessibility) to aaSettings
            ServerAction.OPEN_AA_SETTINGS -> aaSettings to retry
            null -> null to aaSettings
        }
        WarningCard(
            StatusKind.ERROR, R.drawable.ic_ms_error, stringResource(R.string.warn_server_title), stringResource(R.string.server_warning),
            primary, secondary, tag = "server_warning", more = more,
        )
    }
}

/**
 * Once the projection is off, the automatic switch says so only when it could not turn Android Auto's server off;
 * a server turned off as it should be (STOPPED) says nothing.
 */
@Composable
internal fun AfterProjectionLine(p: Projection, server: ServerUi) {
    if (!MainLook.autoShown(server) || p.phase != Phase.STOPPED || !saidAfterProjection(server.result)) return
    ScreenText.server(server.result)?.let {
        Text(
            stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("aa_server_status"),
        )
    }
}

/** Whether [result] is worth a line on the start screen once the projection is off: only a server left on. */
internal fun saidAfterProjection(result: ServerResult): Boolean = result == ServerResult.LEFT_ON

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
