package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * "Conexão experimental": four switches, all off by default, each a way of putting less load on the dash's Wi-Fi or of keeping the link up
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
    ), tag = "group_connection_experiments")
}
