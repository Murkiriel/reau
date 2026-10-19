@file:Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
package com.reau.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.NavRow
import com.reau.app.ui.components.RowAction
import com.reau.app.ui.components.SegmentedRow
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.SwitchRow
import com.reau.app.ui.components.color
import com.reau.app.ui.components.hairline

/**
 * "Depuração": folded for the session until tapped; the ride log and the recordings, the detailed log, seeing,
 * sharing and deleting the logs (each deletion asked first; a Snackbar says how much went, [say]) and the days they
 * are kept.
 */
@Composable
internal fun DebugGroup(s: REauSettings, change: (REauSettings) -> Unit, actions: SettingsActions, say: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(16.dp).testTag("debug"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MsIcon(R.drawable.ic_ms_construction, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.debug_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MsIcon(
                if (open) R.drawable.ic_ms_expand_less else R.drawable.ic_ms_expand_more, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant,
                description = stringResource(if (open) R.string.cd_collapse else R.string.cd_expand),
            )
        }
        if (open) {
            for (row in debugRows(s, change, actions, say)) {
                HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                row()
            }
        }
    }
}

@Composable
private fun debugRows(s: REauSettings, change: (REauSettings) -> Unit, actions: SettingsActions, say: (String) -> Unit): List<@Composable () -> Unit> {
    val deleted = deletedText()
    var asking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    asking?.let { withRecordings ->
        DeleteDialog(withRecordings, onCancel = { asking = null }) {
            asking = null
            actions.onDeleteLogs(withRecordings) { files, kb -> say(deleted(files, kb)) }
        }
    }
    return listOfNotNull(
        { RideLog(s, change) },
        {
            SwitchRow(
                stringResource(R.string.record_video), s.recordVideo, { change(s.copy(recordVideo = it)) }, "record_video",
                sub = stringResource(R.string.record_video_sub),
                info = Info(stringResource(R.string.record_video), stringResource(R.string.record_video_info)),
            )
        },
        {
            SwitchRow(
                stringResource(R.string.record_sessions_title), s.recordSessions, { change(s.copy(recordSessions = it)) }, "record_sessions",
                sub = stringResource(R.string.record_sessions_sub),
                info = Info(stringResource(R.string.record_sessions_title), stringResource(R.string.record_sessions_info)),
            )
        },
        {
            SettingSwitch(R.string.detailed_title, R.string.detailed_sub, R.string.detailed_log, s.detailedLog, "detailed_log", now = true) {
                change(s.copy(detailedLog = it))
            }
        },
        { NavRow(stringResource(R.string.action_view_logs), actions.onViewLogs, "view_logs") },
        { ShareRow(actions) },
        { DeleteRow { asking = it } },
        { LogKeepDays(s, change) },
    )
}

/** Sharing the logs, with or without the recordings: nothing leaves the phone otherwise. */
@Composable
private fun ShareRow(actions: SettingsActions) {
    ActionsRow(stringResource(R.string.share_title), listOf(
        RowAction(stringResource(R.string.share_logs_short), "share_logs", R.drawable.ic_ms_share) { actions.onShareLogs(false) },
        RowAction(stringResource(R.string.share_recordings_short), "share_logs_recordings", R.drawable.ic_ms_share) { actions.onShareLogs(true) },
    ), sub = stringResource(R.string.share_sub), tag = "share")
}

/** Deleting the logs, or the logs and the recordings: [ask] opens the confirmation for one or the other. */
@Composable
private fun DeleteRow(ask: (withRecordings: Boolean) -> Unit) {
    ActionsRow(stringResource(R.string.delete_title), listOf(
        RowAction(stringResource(R.string.delete_logs_short), "delete_logs", R.drawable.ic_ms_delete, danger = true) { ask(false) },
        RowAction(stringResource(R.string.delete_recordings_short), "delete_logs_recordings", R.drawable.ic_ms_delete, danger = true) { ask(true) },
    ), sub = stringResource(R.string.delete_sub), tag = "delete")
}

/** The ride log (on by default): what on and off mean, and the whole text behind the (i). */
@Composable
private fun RideLog(s: REauSettings, change: (REauSettings) -> Unit) {
    SwitchRow(
        stringResource(R.string.ride_log_title), s.rideLog, { change(s.copy(rideLog = it)) }, "ride_log",
        sub = stringResource(if (s.rideLog) R.string.ride_log_sub_on else R.string.ride_log_sub_off), now = true,
        info = Info(stringResource(R.string.ride_log_title), stringResource(R.string.ride_log)),
    )
}

/** Log retention: what is older than the days chosen goes by itself. */
@Composable
private fun LogKeepDays(s: REauSettings, change: (REauSettings) -> Unit) {
    val options = REauSettings.LOG_KEEP_DAYS.map { days ->
        val label = if (days == 0) stringResource(R.string.log_keep_never) else pluralStringResource(R.plurals.log_keep_days_n, days, days)
        label to "log_keep_$days"
    }
    SegmentedRow(
        stringResource(R.string.log_keep_title), options, REauSettings.LOG_KEEP_DAYS.indexOf(s.logKeepDays),
        { change(s.copy(logKeepDays = REauSettings.LOG_KEEP_DAYS[it])) },
        sub = stringResource(if (s.logKeepDays == 0) R.string.log_keep_sub_never else R.string.log_keep_sub),
        now = true, info = Info(stringResource(R.string.log_keep_title), stringResource(R.string.log_keep_days)), tag = "log_keep",
    )
}

/** Deleting asks first (nothing brings the files back): the warning colour's icon, Cancelar and Apagar outlined in red. */
@Composable
private fun DeleteDialog(withRecordings: Boolean, onCancel: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { MsIcon(R.drawable.ic_ms_delete, 28.dp, StatusKind.CONNECTING.color()) },
        title = { Text(stringResource(R.string.delete_logs_title), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(if (withRecordings) R.string.delete_logs_recordings_text else R.string.delete_logs_text)) },
        confirmButton = {
            OutlinedButton(
                onDelete, Modifier.heightIn(min = 44.dp).testTag("delete_confirm"), shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, colors.error), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onCancel, Modifier.heightIn(min = 44.dp).testTag("delete_cancel")) {
                Text(stringResource(R.string.action_cancel), color = colors.onSurfaceVariant)
            }
        },
        containerColor = colors.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
    )
}

/** A switch with its short [title] and [sub], and the whole old text [long] behind its (i). */
@Suppress("LongParameterList") // the three texts, the value and its tag, the chip, and what a tap hands over
@Composable
internal fun SettingSwitch(title: Int, sub: Int, long: Int, checked: Boolean, tag: String, now: Boolean = false, onChange: (Boolean) -> Unit) {
    SwitchRow(
        stringResource(title), checked, onChange, tag, sub = stringResource(sub), now = now,
        info = Info(stringResource(title), stringResource(long)),
    )
}

/** The credits, at the end of the settings. */
@Composable
internal fun CreditsRow(onOpen: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, hairline(0.18f), shape)) {
        NavRow(stringResource(R.string.action_open_credits), onOpen, "open_credits", icon = R.drawable.ic_ms_favorite)
    }
}

/** The terms of use, to read (the first reading is the gate at the first start). */
@Composable
internal fun TermsRow(onOpen: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, hairline(0.18f), shape)) {
        NavRow(stringResource(R.string.action_open_terms), onOpen, "open_terms", icon = R.drawable.ic_ms_description)
    }
}

/** The text of the Snackbar after a deletion. */
@Composable
internal fun deletedText(): (Int, Long) -> String {
    val resources = LocalResources.current
    return { files, kb -> resources.getString(R.string.logs_deleted, files, kb) }
}
