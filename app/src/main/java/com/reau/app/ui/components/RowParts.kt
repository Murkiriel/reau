@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.REauMono

/** The switch, 52×32: Ouro track with a Grafite knob on; Ouro suave ring and small knob off. */
@Composable
fun REauSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val off = if (dark) colors.secondary else colors.onSurfaceVariant
    Switch(
        checked = checked, onCheckedChange = null, modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary, checkedBorderColor = colors.primary,
            uncheckedThumbColor = off, uncheckedTrackColor = colors.background, uncheckedBorderColor = off,
        ),
    )
}

/** One segment: chosen, Ouro border on primaryContainer with Ouro text (Grafite in the light theme); never wraps. */
@Composable
internal fun Segment(label: String, chosen: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val border = if (chosen) colors.primary else hairline(0.3f)
    Box(
        modifier.heightIn(min = 40.dp).clip(shape).background(if (chosen) colors.primaryContainer else Color.Transparent)
            .border(if (chosen && !dark) 1.5.dp else 1.dp, border, shape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, maxLines = 1, softWrap = false,
            color = if (chosen && dark) colors.primary else colors.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.5.sp, fontWeight = if (chosen && !dark) FontWeight.SemiBold else FontWeight.Medium,
            ),
        )
    }
}

/** The field itself: Grafite, Ouro suave border (Ouro when focused), mono text; the eye shows a password. */
@Composable
internal fun Field(value: String, onChange: (String) -> Unit, tag: String, password: Boolean) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    var shown by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(shape).background(colors.background)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) colors.primary else hairline(0.3f), shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value, onChange, singleLine = true, interactionSource = focus,
            textStyle = REauMono.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = if (password) 48.dp else 14.dp).testTag(tag),
        )
        if (password) {
            IconButton(onClick = { shown = !shown }, modifier = Modifier.align(Alignment.CenterEnd).size(44.dp).testTag("${tag}_eye")) {
                MsIcon(
                    if (shown) R.drawable.ic_ms_visibility_off else R.drawable.ic_ms_visibility, 20.dp, colors.onSurfaceVariant,
                    description = stringResource(if (shown) R.string.cd_hide_password else R.string.cd_show_password),
                )
            }
        }
    }
}

/** A row's outlined button; a [RowAction.danger] one in red. */
@Composable
internal fun RowButton(action: RowAction) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val border = when {
        action.danger -> colors.error.copy(alpha = if (dark) 0.6f else 1f)
        else -> hairline(0.5f)
    }
    val ink = if (action.danger && dark) colors.error else colors.onSurface
    OutlinedButton(
        action.onClick, Modifier.heightIn(min = 40.dp).testTag(action.tag), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, border), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
    ) {
        action.icon?.let {
            MsIcon(it, 18.dp, if (action.danger) colors.error else ink)
            Box(Modifier.size(6.dp))
        }
        Text(action.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false)
    }
}

/**
 * A row with its texts at the left and one button at the right (the known dash and "Esquecer"); [subMono] sets the
 * line in mono, for a name. The texts carry [tag]; the button its own.
 */
@Composable
fun ButtonRow(title: String, sub: String?, tag: String, button: RowAction?, subMono: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag(tag), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            sub?.let {
                Text(
                    it, color = colors.onSurfaceVariant,
                    style = if (subMono) REauMono.copy(fontSize = 13.sp) else MaterialTheme.typography.bodySmall,
                )
            }
        }
        button?.let { RowButton(it) }
    }
}
