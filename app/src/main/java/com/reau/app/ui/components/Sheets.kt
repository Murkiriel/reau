@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark

/**
 * The (i)'s sheet (a ModalBottomSheet): Carvão, radius 26 on top, a 36×4 handle, the title, the whole text (it scrolls
 * when long) and "Entendi". Tags `info_sheet` and `info_sheet_ok`.
 */
@Composable
fun InfoSheet(info: Info, onDismiss: () -> Unit) {
    REauSheet(onDismiss, "info_sheet") {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 40.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(info.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                info.text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            )
            SheetButton(stringResource(R.string.action_understood), "info_sheet_ok", onDismiss)
        }
    }
}

/** A list to pick one from in a sheet (the joystick's 16 actions): radio rows of 48 dp, each [options]' label and tag. */
@Composable
fun PickSheet(title: String, options: List<Pair<String, String>>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit, tag: String = "pick_sheet") {
    REauSheet(onDismiss, tag) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, (label, optionTag) -> PickRow(label, i == selected, optionTag) { onPick(i) } }
            }
        }
    }
}

/** The sheets' frame: Carvão (Marfim with a border in the light theme), radius 26 on top, a 36×4 handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun REauSheet(onDismiss: () -> Unit, tag: String, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 14.dp).size(36.dp, 4.dp).background(hairline(0.5f), RoundedCornerShape(2.dp)))
        },
        modifier = Modifier.testTag(tag),
    ) { content() }
}

/** The sheet's closing button: outlined, 48 dp, Ouro in the dark theme. */
@Composable
private fun SheetButton(label: String, tag: String, onClick: () -> Unit) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp).testTag(tag), shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, if (dark) colors.primary else colors.secondary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) colors.primary else colors.onSurface),
    ) { Text(label, style = MaterialTheme.typography.bodyLarge) }
}

/** One option of a pick sheet: the radio icon and the label; the chosen one on a faint gold. */
@Composable
private fun PickRow(label: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val radio = if (chosen) R.drawable.ic_ms_radio_button_checked else R.drawable.ic_ms_radio_button_unchecked
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (chosen) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 24.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MsIcon(radio, 22.dp, if (chosen) accentTint() else colors.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
}

/** One tab of a [TabRail]: what it says, an optional line under it, its test tag. */
data class TabOption(val label: String, val sub: String?, val tag: String)

/**
 * A two-way rail (Modo mapa / Controle de mídia, Encaixe dos apps / do menu): a Carvão track with tabs of 44 dp; the
 * chosen one Grafite with Ouro text (Ouro with Grafite text in the light theme).
 */
@Composable
fun TabRail(options: List<TabOption>, selected: Int, onSelect: (Int) -> Unit) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val chrome = com.reau.app.ui.theme.LocalREauStatus.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(chrome.chrome).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, (label, sub, tag) ->
            val chosen = i == selected
            val fill = when {
                !chosen -> Color.Transparent
                dark -> colors.background
                else -> colors.primary
            }
            val ink = when {
                !chosen -> chrome.onChrome
                dark -> colors.primary
                else -> colors.onPrimary
            }
            Column(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                    .selectable(selected = chosen, role = Role.Tab) { onSelect(i) }.padding(6.dp).testTag(tag),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label, color = ink, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                sub?.let { Text(it, color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)) }
            }
        }
    }
}

/** A Material Symbols icon in a 40 dp circle with a thin Ouro suave border (the "Seus dados" summary lines). */
@Composable
fun RoundIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(modifier.size(40.dp).clip(shape).border(1.dp, hairline(0.4f), shape), contentAlignment = Alignment.Center) {
        MsIcon(icon, 22.dp, accentTint())
    }
}
