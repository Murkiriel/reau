package com.reau.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.dash.DashConfig
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.LockedRow
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauSpace

// The stream's rate ceiling, bitrate, mode and keyframe are fixed, in two profiles (the four rows, under the Android
// Auto group of SettingsScreen.kt, are only shown). Each used to be a choice.

/** The two video profiles by name and one short line each, and nothing else: no number (those are behind [ProfileDetailsRow]). */
@Composable
internal fun ProfileNameRow(titleRes: Int, subRes: Int, tag: String) {
    LockedRow(stringResource(titleRes), stringResource(R.string.profile_fixed), tag, sub = stringResource(subRes))
}

/**
 * "Detalhes": a row that opens and closes the numbers of the fixed profiles ([StreamProfileRows]); it starts closed ([ProfileDetails.START_OPEN])
 * and is closed again at the next visit to the screen.
 */
@Composable
internal fun ProfileDetailsRow(s: REauSettings) {
    var open by rememberSaveable { mutableStateOf(ProfileDetails.START_OPEN) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = REauSpace.touch)
                .padding(horizontal = 16.dp, vertical = 14.dp).testTag("profile_details"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.profile_details_title), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.profile_details_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MsIcon(
                if (open) R.drawable.ic_ms_expand_less else R.drawable.ic_ms_expand_more, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant,
                description = stringResource(if (open) R.string.cd_collapse else R.string.cd_expand),
            )
        }
        if (ProfileDetails.rowsShown(open).isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
            StreamProfileRows(s)
        }
    }
}

/**
 * The stream's four values, fixed (REauSettings.sanitized): two profiles, nothing else. Shown, not to be touched.
 * The navigation profiles below choose between them.
 */
@Composable
internal fun StreamProfileRows(s: REauSettings) {
    LockedRow(
        stringResource(R.string.fps_title), "${s.fpsCeiling}", "fps",
        sub = stringResource(R.string.profiles_fps_sub, DashConfig.PROFILE_REDUCED_FPS),
        info = Info(
            stringResource(R.string.fps_title),
            stringResource(
                R.string.profiles_info, DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_FULL_BPS / 1000,
                DashConfig.PROFILE_REDUCED_FPS, DashConfig.PROFILE_REDUCED_BPS / 1000,
            ),
        ),
    )
    LockedRow(
        stringResource(R.string.bitrate_title), stringResource(R.string.bitrate_value, s.streamBitrateBps / 1000), "bitrate",
        sub = stringResource(R.string.profiles_bitrate_sub, DashConfig.PROFILE_REDUCED_BPS / 1000),
    )
    LockedRow(stringResource(R.string.bitrate_mode_title), stringResource(R.string.bitrate_cbr), "bitrate_mode")
    LockedRow(stringResource(R.string.keyframe_title), "${s.keyframeSec} s", "keyframe")
}

/**
 * Fixed (REauSettings.sanitized): the profile by maneuver and the profile by speed are always on and, when they
 * disagree, the pessimistic one (profile B, the reduced) wins (fixed too, with no row). Shown, not to be touched.
 */
@Composable
internal fun NavProfileRows() {
    val on = stringResource(R.string.profiles_on)
    LockedRow(
        stringResource(R.string.maneuver_fps_title), on, "maneuver_fps_profile",
        info = Info(stringResource(R.string.maneuver_fps_title), stringResource(R.string.maneuver_profile_info)),
    )
    LockedRow(
        stringResource(R.string.speed_fps_title), on, "speed_fps_profile",
        info = Info(stringResource(R.string.speed_fps_title), stringResource(R.string.speed_profile_info)),
    )
}
