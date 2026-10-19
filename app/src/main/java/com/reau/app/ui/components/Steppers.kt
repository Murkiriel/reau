@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.REauCounterValue
import com.reau.app.ui.theme.REauMono

/** The settings' compact counter: 40 dp buttons and the value in mono (tags `<tag>_minus`, `<tag>_value`, `<tag>_plus`). */
@Composable
fun CompactStepper(value: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton(Modifier.size(40.dp), "${tag}_minus", onMinus, icon = R.drawable.ic_ms_remove, description = R.string.cd_less)
        Text(
            value, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface,
            style = REauMono.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.widthIn(min = 58.dp).testTag("${tag}_value"),
        )
        StepButton(Modifier.size(40.dp), "${tag}_plus", onPlus, icon = R.drawable.ic_ms_add, description = R.string.cd_more)
    }
}

/**
 * The fit's fine and coarse counter (REau's own): [label] with its [hint] at the right, then −[bigStep], −[step], the
 * value in mono, +[step], +[bigStep]; every press stays inside [range]. Tags as the old counters:
 * `<tag>_minus<bigStep>`, `<tag>_minus`, `<tag>_value`, `<tag>_plus`, `<tag>_plus<bigStep>`; an [info] puts the (i) beside the
 * label (`<tag>_info`).
 */
@Composable
fun FineStepper(
    label: String,
    hint: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    tag: String,
    step: Int = 1,
    bigStep: Int = 10,
    format: (Int) -> String = { it.toString() },
    info: Info? = null,
) {
    val press = { delta: Int -> { onChange(ComponentLogic.stepped(value, delta, range)) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // 28 dp tall whether or not there is an (i), so every counter of a screen is spaced the same.
            Row(Modifier.weight(1f).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                if (info != null) InfoButton(info, "${tag}_info")
            }
            Text(hint, style = REauMono.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton(Modifier.width(54.dp).height(44.dp), "${tag}_minus$bigStep", press(-bigStep), text = "−$bigStep")
            StepButton(Modifier.width(44.dp).height(44.dp), "${tag}_minus", press(-step), icon = R.drawable.ic_ms_remove, description = R.string.cd_less)
            Text(
                format(value), maxLines = 1, softWrap = false, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface,
                style = REauCounterValue, modifier = Modifier.weight(1f).testTag("${tag}_value"),
            )
            StepButton(Modifier.width(44.dp).height(44.dp), "${tag}_plus", press(step), icon = R.drawable.ic_ms_add, description = R.string.cd_more)
            StepButton(Modifier.width(54.dp).height(44.dp), "${tag}_plus$bigStep", press(bigStep), text = "+$bigStep")
        }
    }
}

/** A counter's button: Grafite, radius 12, a thin Ouro suave border; an icon (− +) or a mono text (−10 +10). */
@Composable
private fun StepButton(
    modifier: Modifier,
    tag: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    description: Int? = null,
    text: String? = null,
    iconSize: Dp = 20.dp,
) {
    val shape = MaterialTheme.shapes.small
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.background).border(1.dp, hairline(0.35f), shape)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) MsIcon(icon, iconSize, ink, description = description?.let { stringResource(it) })
        if (text != null) Text(text, color = ink, style = REauMono.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}
