package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.Edition
import com.reau.app.R
import com.reau.app.settings.AppPermissions
import com.reau.app.settings.AppPermissions.Kind
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.GoldButton
import com.reau.app.ui.components.NavRow
import com.reau.app.ui.components.PermissionRow
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauMono

/**
 * The permissions page: every permission REau asks for, grouped by who grants it with how many are granted, each with
 * its state now; a tap opens why REau uses it (never cut) and, when it is missing, "Liberar". "Pedir as que faltam (N)"
 * on top while one is missing. A permission whose function is off (the switches in [functions], location not allowed) is
 * listed as optional: not counted as missing and not asked in bulk. The rows are read again each time the page opens and when REau comes back to the front.
 */
@Composable
fun PermissionsScreen(
    rows: List<AppPermissions.Row>,
    actions: PermissionsActions,
    sdk: Int = android.os.Build.VERSION.SDK_INT,
    locationAllowed: Boolean = false,
    functions: AppPermissions.Functions = AppPermissions.Functions(),
) {
    val missing = AppPermissions.askAll(rows, sdk, locationAllowed, functions)
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.permissions_title), actions.onBack, titleTag = "permissions_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(stringResource(R.string.permissions_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (missing.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GoldButton(
                            CardAction(stringResource(R.string.permissions_ask_all_n, missing.size), "ask_all") { actions.onAskAll(missing) },
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        )
                        Text(
                            stringResource(R.string.permissions_ask_all_note), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                for (kind in Kind.entries) {
                    val ofKind = rows.filter { it.explained.kind == kind }
                    if (ofKind.isNotEmpty()) Group(kind, ofKind, sdk, locationAllowed, functions, actions)
                }
            }
        }
    }
}

/** One group: its label with "granted/total", then a card with its rows and, for two of them, the way to Android's page. */
@Composable
private fun Group(
    kind: Kind,
    rows: List<AppPermissions.Row>,
    sdk: Int,
    locationAllowed: Boolean,
    functions: AppPermissions.Functions,
    actions: PermissionsActions,
) {
    val shape = MaterialTheme.shapes.large
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}.testTag("permissions_section_$kind"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(sectionOf(kind)).uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.permissions_count, rows.count { it.granted }, rows.size),
                style = REauMono.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                Permission(row, AppPermissions.ask(row, sdk, locationAllowed), AppPermissions.optional(row, functions, locationAllowed), actions.onAsk)
            }
            when (kind) {
                Kind.SPECIAL -> {
                    // After a clean install the ⋮ with "Permitir configurações restritas" is in the app info.
                    if (rows.any { it.restricted }) Way(R.string.action_allow_restricted, "permissions_allow_restricted", actions.onOpenAppSettings)
                    // The Lite edition has no accessibility service to open the settings of.
                    if (Edition.hasAccessibility) {
                        AccessibilityAsk(actions.onOpenAccessibility) { ask -> Way(R.string.action_open_accessibility, "permissions_open_accessibility", ask) }
                    }
                }
                Kind.ASKED -> Way(R.string.permissions_open_app_settings, "permissions_open_app_settings", actions.onOpenAppSettings)
                else -> Unit
            }
        }
    }
}

/** A row of a permission: a stuck accessibility says "Parado" and, in its why, what to do (never cut). */
@Composable
private fun Permission(row: AppPermissions.Row, ask: AppPermissions.Ask?, optional: Boolean, onAsk: (AppPermissions.Ask) -> Unit) {
    val e = row.explained
    val state = when {
        row.stuck -> R.string.permission_special_stuck_short
        e.kind == Kind.SPECIAL && row.granted -> R.string.permission_special_on
        e.kind == Kind.SPECIAL -> R.string.permission_special_off
        row.granted -> R.string.permission_granted
        // Not granted, and its function is off: nothing is missing, it only waits for the switch.
        optional -> R.string.permission_optional
        else -> R.string.permission_denied
    }
    val first = when {
        row.stuck -> stringResource(R.string.permission_special_stuck)
        row.restricted -> stringResource(R.string.permission_special_restricted)
        else -> null
    }
    val why = stringResource(e.why).let { if (first != null) first + "\n\n" + it else it }
    PermissionRow(
        stringResource(e.title), e.permission.takeIf { e.kind != Kind.SPECIAL }, row.granted, stringResource(state), why,
        ask?.let { { onAsk(it) } }, "permission_${e.permission}", askTag = "ask_${e.permission}",
    )
}

/** The way to the Android page where a group's permissions are switched. */
@Composable
private fun Way(label: Int, tag: String, onClick: () -> Unit) {
    HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
    NavRow(stringResource(label), onClick, tag)
}

private fun sectionOf(kind: Kind): Int = when (kind) {
    Kind.SPECIAL -> R.string.permissions_section_special
    Kind.ASKED -> R.string.permissions_section_asked
    Kind.INSTALL -> R.string.permissions_section_install
    Kind.LIBRARY -> R.string.permissions_section_library
}
