package com.reau.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.ButtonRow
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.SupportButton
import com.reau.app.ui.components.FieldRow
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.RowAction
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * The bike's Wi-Fi on a screen of its own ("Dados da moto", with everything the dash says: [BikeDataGroups]), from the
 * main screen's shortcut and from Ajustes: the dash known (and "Esquecer"), the beginning of its name, its password and
 * joining by itself. [onBack] leaves it; the rest goes through [actions], as in the settings.
 */
@Composable
fun WifiScreen(settings: REauSettings, pairing: PairingUi, actions: SettingsActions, scanAllowed: Boolean = true, onBack: () -> Unit) {
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.tile_wifi), onBack, titleTag = "wifi_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WifiGroup(settings, pairing, actions, scanAllowed)
                // Everything the dash says goes on this screen.
                BikeDataGroups()
            }
        }
    }
}

/**
 * The dash known, or not yet: then (as after a clean install) how REau finds it, while it cannot see the Wi-Fi list
 * ([scanAllowed] false), with the way to allow the location (only for that list), and a field for the name.
 */
@Composable
private fun WifiGroup(s: REauSettings, pairing: PairingUi, actions: SettingsActions, scanAllowed: Boolean) {
    val known = pairing.ssid.isNotBlank()
    // What the rider types stays on screen while they type (the name is known from the first letter on).
    var typed by rememberSaveable { mutableStateOf("") }
    // The name hidden until the rider asks to see it.
    var shown by rememberSaveable { mutableStateOf(false) }
    val finding = listOfNotNull<@Composable () -> Unit>(
        when {
            known -> null
            // REau finds and fills in the Wi-Fi by itself (DashFinder).
            scanAllowed -> ({ Searching() })
            else -> ({ FindDashHelp(actions.onAskWifiList) })
        },
        if ((known || scanAllowed) && typed.isEmpty()) {
            null
        } else {
            {
                FieldRow(
                    stringResource(R.string.label_ssid_full), typed, { typed = it; actions.onPairing(pairing.copy(ssid = it.trim())) }, "ssid_full",
                    sub = stringResource(R.string.ssid_full_sub),
                )
            }
        },
    )
    SettingsGroup(R.drawable.ic_ms_wifi, stringResource(R.string.bike_group_wifi), finding + listOf(
        {
            if (known) {
                val show = RowAction(
                    stringResource(if (shown) R.string.action_hide_name else R.string.action_show_name), "show_ssid",
                    if (shown) R.drawable.ic_ms_visibility_off else R.drawable.ic_ms_visibility,
                ) { shown = !shown }
                ActionsRow(
                    stringResource(R.string.pairing_known_title),
                    listOf(show, RowAction(stringResource(R.string.action_forget), "forget_dash", onClick = actions.onForgetDash)),
                    sub = if (shown) pairing.ssid else SsidMask.of(pairing.ssid), tag = "ssid_known",
                    info = Info(stringResource(R.string.pairing_known_title), stringResource(R.string.pairing_known_info)),
                )
            } else {
                ButtonRow(stringResource(R.string.pairing_unknown_title), stringResource(R.string.pairing_unknown_sub), "ssid_known", null)
            }
        },
        {
            // Off by default; switching it on suggests the bike's Wi-Fi to Android.
            SwitchRow(
                stringResource(R.string.auto_join_title), s.autoJoinWifi, { actions.onSettings(s.copy(autoJoinWifi = it).sanitized()) }, "auto_join_wifi",
                info = Info(stringResource(R.string.auto_join_title), stringResource(R.string.auto_join_wifi)),
            )
        },
    ), tag = "group_wifi")
}

/** Searching by itself: the name fills in on its own once the dash's Wi-Fi is in the list. */
@Composable
private fun Searching() {
    Text(
        stringResource(R.string.dash_searching), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("dash_searching"),
    )
}

/** Why the dash is not found yet and the way to the location, asked only for the Wi-Fi list. */
@Composable
private fun FindDashHelp(onAskWifiList: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.find_dash_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("find_dash_help"),
        )
        SupportButton(CardAction(stringResource(R.string.action_allow_wifi_list), "allow_wifi_list", onAskWifiList))
    }
}
