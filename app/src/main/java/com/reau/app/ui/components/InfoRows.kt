@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.REauMono

/**
 * A permission: the [name], its technical name in mono, and at the right its state ([state], with a green tick when
 * [granted], a red cross when not). A tap opens what it is for ([why], never cut) and, when it is missing and can be
 * asked for ([onAsk]), an outlined "Liberar" ([askTag]). The why is `<tag>_why`; [tech] may be absent (the special
 * accesses have none).
 */
@Composable
fun PermissionRow(
    name: String,
    tech: String?,
    granted: Boolean,
    state: String,
    why: String,
    onAsk: (() -> Unit)?,
    tag: String,
    askTag: String = "${tag}_ask",
) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val tone = if (granted) StatusKind.OK.color() else StatusKind.ERROR.color()
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = colors.onSurface)
                tech?.let { Text(it, style = REauMono.copy(fontSize = 11.sp, lineHeight = 14.sp), color = colors.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MsIcon(if (granted) R.drawable.ic_ms_check_circle else R.drawable.ic_ms_cancel, 18.dp, tone)
                Text(
                    state, color = if (dark) tone else colors.onSurface, modifier = Modifier.testTag("${tag}_state"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                )
            }
            Chevron(open)
        }
        if (open) {
            Text(
                why, color = colors.onSurface.copy(alpha = 0.85f), modifier = Modifier.testTag("${tag}_why"),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 21.sp),
            )
            if (!granted && onAsk != null) {
                OutlinedButton(
                    onAsk, Modifier.heightIn(min = 44.dp).testTag(askTag), shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, if (dark) colors.primary else colors.secondary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) colors.primary else colors.onSurface),
                ) { Text(stringResource(R.string.action_grant), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/**
 * A block of "O REau e os seus dados": [icon] and [title] with a chevron; open, the whole [text] (never cut), indented
 * under the title. The text is `<tag>_text`.
 */
@Composable
fun ExpandableBlock(@DrawableRes icon: Int, title: String, text: String, expanded: Boolean, onToggle: () -> Unit, tag: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("${tag}_head"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MsIcon(icon, 22.dp, accentTint())
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp), color = colors.onSurface, modifier = Modifier.weight(1f))
            Chevron(expanded)
        }
        if (expanded) {
            Text(
                text, color = colors.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 50.dp, end = 16.dp, bottom = 16.dp).testTag("${tag}_text"),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp, lineHeight = 23.sp),
            )
        }
    }
}

/**
 * A project of the credits: its [name], "© holder · licence" ([line]) in mono and its [role]; the copy icon at the
 * right. A tap hands the address to [onCopy]; [shown], when given, is the address under the role.
 */
@Composable
fun CreditRow(name: String, line: String, role: String, onCopy: () -> Unit, tag: String, shown: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onCopy).padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = colors.onSurface)
            if (line.isNotEmpty()) Text(line, style = REauMono.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = colors.onSurfaceVariant)
            Text(role, style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = 0.8f))
            shown?.let { Text(it, style = REauMono.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = colors.onSurfaceVariant) }
        }
        MsIcon(R.drawable.ic_ms_content_copy, 20.dp, colors.onSurfaceVariant, description = stringResource(R.string.cd_copy))
    }
}

/** The open/closed chevron of a row that unfolds. */
@Composable
private fun Chevron(open: Boolean) {
    MsIcon(
        if (open) R.drawable.ic_ms_expand_less else R.drawable.ic_ms_expand_more, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant,
        description = stringResource(if (open) R.string.cd_collapse else R.string.cd_expand),
    )
}
