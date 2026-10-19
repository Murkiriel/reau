package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.dash.DashConfig
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.LockedRow

// The stream's rate ceiling, bitrate, mode and keyframe are fixed, in two profiles (the four rows, under the Android
// Auto group of SettingsScreen.kt, are only shown). Each used to be a choice.

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
