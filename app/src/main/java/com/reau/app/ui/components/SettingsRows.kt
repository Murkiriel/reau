// One small function per case (splitting them would only scatter the same thing), and a component takes one parameter per
// thing it shows, each with its default (as Material 3's).
@file:Suppress("TooManyFunctions", "LongParameterList")

package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reau.app.R

/**
 * A group of the settings: the [icon] and [title] as a label, then a Carvão card with the [rows], a thin line between
 * each two.
 */
@Composable
fun SettingsGroup(@DrawableRes icon: Int?, title: String?, rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier, tag: String = "") {
    val shape = MaterialTheme.shapes.large
    Column(modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) GroupLabel(icon, title)
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                row()
            }
        }
    }
}

/** A group's label: a small icon and the title in spaced capitals. */
@Composable
fun GroupLabel(@DrawableRes icon: Int?, title: String, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { MsIcon(it, 18.dp, ink) }
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

/** An on/off setting: the whole row is the switch (tag [tag]), as the settings always were. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    sub: String? = null,
    now: Boolean = false,
    info: Info? = null,
    subColor: Color? = null,
    below: (@Composable () -> Unit)? = null,
) {
    SettingRow(
        title, sub, now, info, subColor, tag,
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag), null,
        trailing = { REauSwitch(checked) },
        below = below,
    )
}

/** A number with − and + at the right (compact counter, tags `<tag>_minus`, `<tag>_value`, `<tag>_plus`). */
@Composable
fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    tag: String,
    sub: String? = null,
    now: Boolean = false,
    info: Info? = null,
) {
    SettingRow(title, sub, now, info, null, tag, Modifier, "${tag}_row", trailing = { CompactStepper(value, tag, onMinus, onPlus) })
}

/** One choice among a few, as segments under the title (each tagged by its option, as the radios were). */
@Composable
fun SegmentedRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    sub: String? = null,
    now: Boolean = false,
    info: Info? = null,
    tag: String = "",
) {
    SettingRow(title, sub, now, info, null, tag, Modifier, tag, below = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, (label, optionTag) ->
                Segment(label, i == selected, optionTag, Modifier.weight(1f)) { onSelect(i) }
            }
        }
    })
}

/** A row that opens another page: an optional leading [icon], the title and a chevron. */
@Composable
fun NavRow(title: String, onClick: () -> Unit, tag: String, sub: String? = null, @DrawableRes icon: Int? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { MsIcon(it, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        MsIcon(R.drawable.ic_ms_chevron_right, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A text to type (the dash's name, its password): 48 dp, mono, Ouro when focused; a password hides behind the eye. */
@Composable
fun FieldRow(title: String, value: String, onChange: (String) -> Unit, tag: String, password: Boolean = false, sub: String? = null) {
    SettingRow(title, sub, false, null, null, tag, Modifier, null, below = { Field(value, onChange, tag, password) })
}

/** Buttons under a title (Compartilhar, Apagar...), wrapping when they do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionsRow(title: String?, actions: List<RowAction>, sub: String? = null, tag: String = "", info: Info? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null || sub != null) {
            Box(Modifier.semantics(mergeDescendants = true) {}.testTag(tag)) { RowTexts(title.orEmpty(), sub, false, info, null, tag) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in actions) RowButton(action)
        }
    }
}

/**
 * The common shape of a settings row: texts at the left, [trailing] at the right, [below] under them. [modifier] is the
 * top line's (a switch's toggle); [textsTag], when given, tags the texts as one node (the tests read title and line).
 * What is [below] stays out of the header: a button there never switches the row.
 */
@Composable
internal fun SettingRow(
    title: String,
    sub: String?,
    now: Boolean,
    info: Info?,
    subColor: Color?,
    tag: String,
    modifier: Modifier,
    textsTag: String?,
    trailing: @Composable () -> Unit = {},
    below: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(modifier).padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val texts = if (textsTag == null) Modifier else Modifier.semantics(mergeDescendants = true) {}.testTag(textsTag)
            Box(Modifier.weight(1f).then(texts)) { RowTexts(title, sub, now, info, subColor, tag) }
            trailing()
        }
        below?.let { Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) { it() } }
    }
}

/**
 * The (i) beside a title: a round 28 dp tap target, tagged [tag] (`<row>_info`; the tests list which options carry it),
 * that opens [info] in its sheet.
 */
@Composable
fun InfoButton(info: Info, tag: String) {
    var showInfo by rememberSaveable(info.title) { mutableStateOf(false) }
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { showInfo = true }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { MsIcon(R.drawable.ic_ms_info, 19.dp, MaterialTheme.colorScheme.onSurfaceVariant, description = stringResource(R.string.cd_info)) }
    if (showInfo) InfoSheet(info) { showInfo = false }
}

/** A row's title (with the NA HORA chip and the (i)) and its supporting line. */
@Composable
private fun RowTexts(title: String, sub: String?, now: Boolean, info: Info?, subColor: Color?, tag: String) {
    // An experimental or test setting has its letters in red (not standard, may be removed).
    val red = com.reau.app.ui.ExperimentalMark.marks(title, sub)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title.isNotEmpty()) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall,
                    color = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // Tagged `<row>_now_chip`: the tests list which rows carry the chip.
            if (now) NowChip(Modifier.testTag("${tag}_now_chip"))
            if (info != null) InfoButton(info, "${tag}_info")
        }
        sub?.let {
            val ink = if (red) MaterialTheme.colorScheme.error else subColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            Text(it, style = MaterialTheme.typography.bodySmall, color = ink)
        }
    }
}
