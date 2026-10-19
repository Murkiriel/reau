@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.ui.theme.LocalREauDark

/**
 * The joystick's cross (REau's own): a 300 dp Carvão disc with the four directions (88 dp, radius 24) and the centre
 * (92 dp, round), each with its icon and the short name of what it does ([labels]). A [locked] control shows a lock and
 * fades; it can still be picked, to read why. Tags `<tag>_<control>` (`dpad_up`...).
 */
@Composable
fun DPadMap(labels: Map<Control, String>, locked: Set<Control>, selected: Control?, onSelect: (Control) -> Unit, tag: String = "dpad") {
    Box(Modifier.size(DISC).testTag(tag)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.22f), CircleShape))
        for ((control, place) in PLACES) {
            PadButton(control, labels[control].orEmpty(), control in locked, control == selected, place, "${tag}_${control.name.lowercase()}") {
                onSelect(control)
            }
        }
    }
}

/** Where a button sits on the disc and how big it is. */
private class Place(val x: Dp, val y: Dp, val size: Dp)

@Composable
private fun PadButton(control: Control, label: String, locked: Boolean, chosen: Boolean, place: Place, tag: String, onClick: () -> Unit) {
    val dark = LocalREauDark.current
    val shape = if (control == Control.CENTER) CircleShape else RoundedCornerShape(24.dp)
    val ink = padInk(locked, chosen, dark)
    Column(
        Modifier.offset(place.x, place.y).size(place.size).clip(shape).background(ink.fill).border(1.5.dp, ink.border, shape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick).padding(6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (locked) {
            MsIcon(R.drawable.ic_ms_lock, 30.dp, ink.icon, description = stringResource(R.string.cd_locked))
        } else {
            MsIcon(ICONS.getValue(control), 30.dp, ink.icon)
        }
        Text(
            label, color = ink.label, textAlign = TextAlign.Center, maxLines = 2,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 12.5.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** A cross button's colours: its border, its fill, its icon and its label. */
private class PadInk(val border: Color, val fill: Color, val icon: Color, val label: Color)

/** Chosen: Ouro (a pale gold fill with Grafite text in the light theme). Locked: faded, a lock instead of the arrow. */
@Composable
private fun padInk(locked: Boolean, chosen: Boolean, dark: Boolean): PadInk {
    val colors = MaterialTheme.colorScheme
    return when {
        locked -> PadInk(
            if (chosen) colors.secondary else hairline(0.15f), colors.background,
            colors.onSurface.copy(alpha = 0.45f), if (dark) colors.secondary.copy(alpha = 0.7f) else colors.onSurface.copy(alpha = 0.45f),
        )
        !chosen -> PadInk(hairline(0.3f), colors.background, colors.onSurface, if (dark) colors.secondary else colors.onSurface)
        dark -> PadInk(colors.primary, colors.primary.copy(alpha = 0.14f), colors.primary, colors.primary)
        else -> PadInk(colors.primary, colors.primaryContainer, colors.onSurface, colors.onSurface)
    }
}

private val DISC = 300.dp

private val PLACES = linkedMapOf(
    Control.UP to Place(106.dp, 14.dp, 88.dp),
    Control.LEFT to Place(14.dp, 106.dp, 88.dp),
    Control.CENTER to Place(104.dp, 104.dp, 92.dp),
    Control.RIGHT to Place(198.dp, 106.dp, 88.dp),
    Control.DOWN to Place(106.dp, 198.dp, 88.dp),
)

private val ICONS = mapOf(
    Control.UP to R.drawable.ic_ms_keyboard_arrow_up,
    Control.DOWN to R.drawable.ic_ms_keyboard_arrow_down,
    Control.LEFT to R.drawable.ic_ms_keyboard_arrow_left,
    Control.RIGHT to R.drawable.ic_ms_keyboard_arrow_right,
    Control.CENTER to R.drawable.ic_ms_radio_button_checked,
)
