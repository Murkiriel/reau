package com.reau.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.service.AaServerStarter
import com.reau.app.settings.AppLanguage
import com.reau.app.settings.AppLanguages
import com.reau.app.settings.MapTheme
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.InfoSheet
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.NavRow
import com.reau.app.ui.components.NowChip
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.RowAction
import com.reau.app.ui.components.SegmentedRow
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.StepperRow
import com.reau.app.ui.components.SwitchRow
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.color
import com.reau.app.ui.components.hairline
import kotlinx.coroutines.launch

/**
 * The settings: the "NA HORA" strip, the groups of every day (the dash, Waze's map, what the dash shows, the joystick,
 * Android Auto, the card), "Depuração" folded, and the credits at the end. Each switch's long text of before is whole
 * behind its (i); the titles and lines are short.
 */
@Composable
fun SettingsScreen(
    settings: REauSettings,
    actions: SettingsActions,
    onOpenJoystick: () -> Unit = {},
    accessibility: AaServerStarter.Accessibility = AaServerStarter.Accessibility.OFF,
) {
    val change: (REauSettings) -> Unit = { actions.onSettings(it.sanitized()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.settings_title), actions.onBack, titleTag = "settings_title")
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    NowStrip()
                    LanguageGroup(actions.onLanguage)
                    MapGroup(settings, change)
                    OnDashGroup(settings, change)
                    JoystickGroup(settings, change, onOpenJoystick)
                    // Its only row switches the server on by accessibility, which the Lite edition does not have.
                    if (com.reau.app.Edition.hasAccessibility) AndroidAutoGroup(settings, change, accessibility, actions.onOpenAccessibility)
                    VideoProfilesGroup(settings)
                    CardGroup(settings, change, actions.onPickCardImage)
                    DebugGroup(settings, change, actions, say)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    Snackbar(it, Modifier.testTag("snackbar"), shape = MaterialTheme.shapes.small)
                }
            }
        }
    }
}

/** The language of the app: the phone's own, Portuguese or English; the screens are redrawn at once. */
@Composable
private fun LanguageGroup(onLanguage: (AppLanguage) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val names = mapOf(
        AppLanguage.AUTO to R.string.language_auto, AppLanguage.PT to R.string.language_pt, AppLanguage.EN to R.string.language_en,
    )
    // Held here, not read from the system at each draw: choosing the language the phone already speaks (Portuguese on a
    // Portuguese phone, or back to Automatic from it) changes no configuration, so the system recreates nothing and
    // nothing would redraw the highlight. A real change recreates the screen and this starts again from the saved choice.
    var chosen by remember { mutableStateOf(AppLanguages.current(context)) }
    SettingsGroup(R.drawable.ic_ms_tune, stringResource(R.string.group_language), listOf(
        {
            SegmentedRow(
                stringResource(R.string.language_title), AppLanguage.entries.map { stringResource(names.getValue(it)) to "language_${it.name}" },
                AppLanguage.entries.indexOf(chosen), {
                    chosen = AppLanguage.entries[it]
                    onLanguage(chosen)
                },
                sub = stringResource(R.string.language_sub), now = true, tag = "language",
            )
        },
    ), tag = "group_language")
}

/** The strip on top: the NA HORA chip and what it means; a tap (or its (i)) opens the whole text. */
@Composable
private fun NowStrip() {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, hairline(0.3f), shape).clickable(role = Role.Button) { open = true }
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("applies_next_time"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NowChip()
        Text(
            stringResource(R.string.settings_now_strip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { open = true }.testTag("applies_next_time_info"),
            contentAlignment = Alignment.Center,
        ) { MsIcon(R.drawable.ic_ms_info, 20.dp, MaterialTheme.colorScheme.onSurfaceVariant, description = stringResource(R.string.cd_info)) }
    }
    if (open) InfoSheet(Info(stringResource(R.string.settings_now_title), stringResource(R.string.applies_next_time))) { open = false }
}

/**
 * Waze's map: its theme (taken at once) and, right under it, the location for the theme by the sun. The dash's Wi-Fi
 * is not here (it has its own screen, from the main screen); nor is the bubble's choice with no route, now always the
 * pin with the time.
 */
@Composable
private fun MapGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    val themeSub = when (s.mapTheme) {
        MapTheme.AUTO -> R.string.map_theme_sub_auto
        MapTheme.DAY -> R.string.map_theme_sub_day
        MapTheme.NIGHT -> R.string.map_theme_sub_night
    }
    val themeNames = mapOf(MapTheme.AUTO to R.string.theme_auto_short, MapTheme.DAY to R.string.theme_day, MapTheme.NIGHT to R.string.theme_night)
    SettingsGroup(R.drawable.ic_ms_map, stringResource(R.string.group_map), listOf(
        {
            SegmentedRow(
                stringResource(R.string.map_theme_title), MapTheme.entries.map { stringResource(themeNames.getValue(it)) to "theme_${it.name}" },
                MapTheme.entries.indexOf(s.mapTheme), { change(s.copy(mapTheme = MapTheme.entries[it])) },
                sub = stringResource(themeSub), now = true, tag = "map_theme",
                info = Info(stringResource(R.string.map_theme_title), stringResource(R.string.theme_auto)),
            )
        },
        {
            // REau uses the location only with this on (off by default).
            SwitchRow(
                stringResource(R.string.use_location_title), s.useLocation, { change(s.copy(useLocation = it)) }, "use_location", now = true,
                sub = stringResource(R.string.use_location_sub),
                info = Info(stringResource(R.string.use_location_title), stringResource(R.string.use_location)),
            )
        },
    ), tag = "group_map")
}

/**
 * What the dash shows besides the picture: the phone's state, who calls, the song and the album's picture. The
 * manoeuvre and distance are not here any more: they are always on (REauSettings.sanitized).
 */
@Composable
private fun OnDashGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingsGroup(R.drawable.ic_ms_speed, stringResource(R.string.group_on_dash), listOf(
        // The phone's real battery, GPS and signal.
        {
            SettingSwitch(
                R.string.phone_status_title, R.string.phone_status_sub, R.string.dash_phone_status, s.dashPhoneStatus, "dash_phone_status",
                now = true,
            ) {
                change(s.copy(dashPhoneStatus = it))
            }
        },
        // Android Auto is told at the next start. No (i) on the next two: their line says all there is to say.
        {
            SwitchRow(
                stringResource(R.string.caller_title), s.dashCaller, { change(s.copy(dashCaller = it)) }, "dash_caller",
                sub = stringResource(R.string.caller_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.music_title), s.dashMusic, { change(s.copy(dashMusic = it)) }, "dash_music",
                sub = stringResource(R.string.music_sub),
            )
        },
        {
            SettingSwitch(R.string.album_art_title, R.string.album_art_sub, R.string.dash_album_art, s.dashAlbumArt, "dash_album_art", now = true) {
                change(s.copy(dashAlbumArt = it))
            }
        },
        // The real time left, the maneuver after the next, the lost-signal icon.
        {
            SettingSwitch(
                R.string.route_extras_title, R.string.route_extras_sub, R.string.dash_route_extras, s.dashRouteExtras, "dash_route_extras",
                now = true,
            ) {
                change(s.copy(dashRouteExtras = it))
            }
        },
    ), tag = "group_on_dash")
}

/** The joystick: its page, answering calls and Gemini. */
@Composable
private fun JoystickGroup(s: REauSettings, change: (REauSettings) -> Unit, onOpenJoystick: () -> Unit) {
    SettingsGroup(R.drawable.ic_ms_gamepad, stringResource(R.string.group_joystick), listOf(
        { NavRow(stringResource(R.string.joystick_nav_title), onOpenJoystick, "open_joystick", sub = stringResource(R.string.joystick_nav_sub)) },
        {
            // Read at each press of the joystick and at each call notice (LiveSides.phoneCalls, AaServerAccessibilityService).
            SettingSwitch(R.string.answer_title, R.string.by_joystick_sub, R.string.answer_calls, s.answerCalls, "answer_calls", now = true) {
                change(s.copy(answerCalls = it))
            }
        },
        { SettingSwitch(R.string.gemini_title, R.string.by_joystick_sub, R.string.gemini, s.gemini, "gemini") { change(s.copy(gemini = it)) } },
    ), tag = "group_joystick")
}

/**
 * Android Auto: the server switched on and off by REau's accessibility service (off by default; on, it says whether
 * the service is on and leads to Android's page for it). The margins are on the "Tela do Android Auto"; the stream's
 * values are in [VideoProfilesGroup].
 */
@Composable
private fun AndroidAutoGroup(s: REauSettings, change: (REauSettings) -> Unit, accessibility: AaServerStarter.Accessibility, onOpen: () -> Unit) {
    val connected = accessibility == AaServerStarter.Accessibility.CONNECTED
    SettingsGroup(R.drawable.ic_ms_directions_car, stringResource(R.string.group_android_auto), listOf(
        {
            SwitchRow(
                stringResource(R.string.server_title), s.aaServerAuto, { change(s.copy(aaServerAuto = it)) }, "aa_server_auto",
                sub = stringResource(if (s.aaServerAuto) R.string.server_sub_on else R.string.server_sub_off),
                info = Info(stringResource(R.string.server_title), stringResource(R.string.aa_server_auto)),
                below = if (s.aaServerAuto) ({ ServerAccessibility(connected, accessibility, onOpen) }) else null,
            )
        },
    ), tag = "group_android_auto")
}

/**
 * The video's two fixed profiles: the stream's frame ceiling, bitrate, mode and keyframe, and the navigation profiles
 * that choose between them. Everything in it is shown and locked.
 */
@Composable
private fun VideoProfilesGroup(s: REauSettings) {
    SettingsGroup(R.drawable.ic_ms_tune, stringResource(R.string.group_video_profiles), listOf(
        { StreamProfileRows(s) },
        { NavProfileRows() },
    ), tag = "group_video_profiles")
}

/** Where REau's accessibility service stands, in green once connected, and the way to it while it is not. */
@Composable
private fun ServerAccessibility(connected: Boolean, accessibility: AaServerStarter.Accessibility, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(ScreenText.accessibility(accessibility)), style = MaterialTheme.typography.bodySmall,
            color = if (connected) StatusKind.OK.color() else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("aa_server_accessibility"),
        )
        if (!connected) {
            AccessibilityAsk(onOpen) { ask ->
                com.reau.app.ui.components.SupportButton(
                    com.reau.app.ui.components.CardAction(stringResource(R.string.action_open_accessibility), "open_accessibility", ask),
                )
            }
        }
    }
}

/** The card mode's picture: which one, picking another, back to the legibility card. */
@Composable
private fun CardGroup(s: REauSettings, change: (REauSettings) -> Unit, onPick: () -> Unit) {
    val actions = listOfNotNull(
        RowAction(stringResource(R.string.action_card_pick), "card_pick", R.drawable.ic_ms_add_photo_alternate, onClick = onPick),
        // "Voltar ao padrão": back to our own test picture, which stays until another one is picked. Always there: the
        // wallpaper is for the rider's own picture and for the test picture on the dash.
        RowAction(stringResource(R.string.action_card_legibility), "card_legibility", R.drawable.ic_ms_restart_alt) {
            change(s.copy(cardImage = REauSettings.DEFAULT_CARD))
        },
    )
    SettingsGroup(R.drawable.ic_ms_image, stringResource(R.string.group_card), listOf(
        {
            ActionsRow(
                stringResource(R.string.card_title), actions, tag = "card_now",
                info = Info(stringResource(R.string.card_title), stringResource(R.string.card_info)),
            )
        },
    ), tag = "group_card")
}
