package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * "Conexão experimental": switches, all off by default (the first four are of 10/11/2026; the band and the start guard came after), each a way of putting less load on the dash's Wi-Fi or of keeping the link up
 * (REauSettings.experimentRtpPacing, experimentDebugDscp, experimentAdaptiveBitrate, experimentWifiBind). With all of them off the app does exactly what it did
 * before them. The lines say "Experimental", which paints the rows red (ExperimentalMark). The first and the third restart the stream and count at once; the other two
 * count from the next connection.
 */
@Composable
internal fun ConnectionExperimentsGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingsGroup(R.drawable.ic_ms_wifi, stringResource(R.string.group_connection_experiments), listOf(
        {
            SwitchRow(
                stringResource(R.string.exp_pacing_title), s.experimentRtpPacing, { change(s.copy(experimentRtpPacing = it)) }, "exp_rtp_pacing",
                sub = stringResource(R.string.exp_pacing_sub), now = true,
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_dscp_title), s.experimentDebugDscp, { change(s.copy(experimentDebugDscp = it)) }, "exp_debug_dscp",
                sub = stringResource(R.string.exp_dscp_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_bitrate_title), s.experimentAdaptiveBitrate,
                { change(s.copy(experimentAdaptiveBitrate = it)) }, "exp_adaptive_bitrate",
                sub = stringResource(R.string.exp_bitrate_sub), now = true,
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_wifi_title), s.experimentWifiBind, { change(s.copy(experimentWifiBind = it)) }, "exp_wifi_bind",
                sub = stringResource(R.string.exp_wifi_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_wifi_band_title), s.experimentWifiBand, { change(s.copy(experimentWifiBand = it)) }, "exp_wifi_band",
                sub = stringResource(R.string.exp_wifi_band_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_start_guard_title), s.experimentStartGuard, { change(s.copy(experimentStartGuard = it)) }, "exp_start_guard",
                sub = stringResource(R.string.exp_start_guard_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_protocol_six_title), s.experimentProtocolSix, { change(s.copy(experimentProtocolSix = it)) }, "exp_protocol_six",
                sub = stringResource(R.string.exp_protocol_six_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_tolerant_rx_title), s.experimentTolerantRx, { change(s.copy(experimentTolerantRx = it)) }, "exp_tolerant_rx",
                sub = stringResource(R.string.exp_tolerant_rx_sub),
            )
        },
    ), tag = "group_connection_experiments")
}

/**
 * "Vídeo experimental": switches of the video's path, all off by default; with all of them off the app does exactly what it did before them.
 * The lines say "Experimental", which paints the rows red (ExperimentalMark).
 */
@Composable
internal fun VideoExperimentsGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingsGroup(R.drawable.ic_ms_tune, stringResource(R.string.group_video_experiments), listOf(
        {
            SwitchRow(
                stringResource(R.string.exp_encoder_watch_title), s.experimentEncoderWatch,
                { change(s.copy(experimentEncoderWatch = it)) }, "exp_encoder_watch",
                sub = stringResource(R.string.exp_encoder_watch_sub), now = true,
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_focus_gap_title), s.experimentFocusGap, { change(s.copy(experimentFocusGap = it)) }, "exp_focus_gap",
                sub = stringResource(R.string.exp_focus_gap_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_night_memory_title), s.experimentNightMemory, { change(s.copy(experimentNightMemory = it)) }, "exp_night_memory",
                sub = stringResource(R.string.exp_night_memory_sub),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_heat_ceiling_title), s.experimentHeatCeiling, { change(s.copy(experimentHeatCeiling = it)) }, "exp_heat_ceiling",
                sub = stringResource(R.string.exp_heat_ceiling_sub), now = true,
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_gps_speed_title), s.experimentGpsSpeed, { change(s.copy(experimentGpsSpeed = it)) }, "exp_gps_speed",
                sub = stringResource(R.string.exp_gps_speed_sub), now = true,
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_color_title), s.experimentColorMatrix, { change(s.copy(experimentColorMatrix = it)) }, "exp_color_matrix",
                sub = stringResource(R.string.exp_color_sub),
            )
        },
    ), tag = "group_video_experiments")
}

/**
 * "Painel experimental": switches of what the dash shows besides the picture, off by default; with all of them off the app does exactly what it did before them.
 * The lines say "Experimental", which paints the rows red (ExperimentalMark). Both act at once, read from the live settings.
 */
@Composable
internal fun DashExperimentsGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingsGroup(R.drawable.ic_ms_tune, stringResource(R.string.group_dash_experiments), listOf(
        {
            SwitchRow(
                stringResource(R.string.exp_weather_age_title), s.experimentWeatherAge, { change(s.copy(experimentWeatherAge = it)) }, "exp_weather_age",
                sub = stringResource(R.string.exp_weather_age_sub), now = true,
            )
        },
        {
            SwitchRow(
                stringResource(R.string.exp_recalc_title), s.experimentRecalcInactive, { change(s.copy(experimentRecalcInactive = it)) }, "exp_recalc_inactive",
                sub = stringResource(R.string.exp_recalc_sub), now = true,
            )
        },
    ), tag = "group_dash_experiments")
}
