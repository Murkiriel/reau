@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark

/**
 * A warning of the main screen (only while something is wrong, above everything): [icon] and [title] in the [kind]'s
 * colour (the title in Grafite in the light theme), the [text] and the lines [more] (each with its test tag), and its
 * actions in two columns: [primary] filled in Ouro, [secondary] outlined; a lone secondary takes the whole line.
 */
@Composable
fun WarningCard(
    kind: StatusKind,
    @DrawableRes icon: Int,
    title: String,
    text: String,
    primary: CardAction?,
    secondary: CardAction?,
    modifier: Modifier = Modifier,
    tag: String = "",
    more: List<Pair<String, String>> = emptyList(),
) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val tone = kind.color()
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer)
            .border(if (dark) 1.dp else 1.5.dp, if (dark) tone.copy(alpha = 0.53f) else tone, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}.testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MsIcon(icon, 22.dp, tone)
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = if (dark) tone else colors.onSurface)
        }
        for ((line, lineTag) in listOf(text to "${tag}_text") + more) {
            Text(
                line, color = colors.onSurface.copy(alpha = if (dark) 0.78f else 0.72f), modifier = Modifier.testTag(lineTag),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
            )
        }
        if (primary != null || secondary != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.let { GoldButton(it, Modifier.weight(1f)) }
                secondary?.let { SupportButton(it, Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A shortcut of the main screen, two to a line: the [icon] (Ouro; in the light theme Grafite on a golden plate) beside
 * [title] and [subtitle] (beside them, not above, so the main screen fits without scrolling). Unavailable ([enabled]
 * false), icon and title fade to 38% and the subtitle says [reason]; a tap then hands the reason to [onUnavailable] (a
 * Snackbar) instead of opening. [external] marks one that leaves REau.
 */
@Composable
fun ShortcutTile(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reason: String? = null,
    external: Boolean = false,
    onUnavailable: (String) -> Unit = {},
    tag: String = "",
) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val fade = if (enabled) 1f else UNAVAILABLE_ALPHA
    val said = if (enabled) subtitle else reason ?: subtitle
    Box(
        modifier.heightIn(min = 64.dp).clip(shape).background(colors.surfaceContainer).border(1.dp, hairline(0.22f), shape)
            .clickable(role = Role.Button) { if (enabled) onClick() else onUnavailable(said) }.testTag(tag),
    ) {
        Row(
            Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(36.dp).alpha(fade), contentAlignment = Alignment.Center) {
                if (dark) {
                    MsIcon(icon, 28.dp, colors.primary)
                } else {
                    Box(Modifier.fillMaxSize().background(colors.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        MsIcon(icon, 22.dp, colors.onPrimary)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.alpha(fade))
                Text(said, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.testTag("${tag}_sub"))
            }
        }
        if (external) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                MsIcon(R.drawable.ic_ms_open_in_new, 16.dp, colors.onSurfaceVariant, description = stringResource(R.string.cd_external))
            }
        }
    }
}

/** The screen's one filled golden button (one at most per screen), 44 dp; [enabled] false greys it and ignores touches. */
@Composable
fun GoldButton(action: CardAction, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Button(
        action.onClick, modifier.heightIn(min = 44.dp).testTag(action.tag), enabled = enabled, shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
    ) { Text(action.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** A supporting button, outlined: Ouro in the dark theme, Ouro suave border and Grafite text in the light one. */
@Composable
fun SupportButton(action: CardAction, modifier: Modifier = Modifier) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        action.onClick, modifier.heightIn(min = 44.dp).testTag(action.tag), shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, if (dark) colors.primary else colors.secondary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) colors.primary else colors.onSurface),
    ) { Text(action.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), textAlign = TextAlign.Center) }
}

/**
 * A quiet button with an [icon] ("Voltar ao padrão", "Mandar só a cartela de teste"): Ouro suave text in the dark
 * theme, Grafite underlined in Ouro in the light one.
 */
@Composable
fun GhostButton(@DrawableRes icon: Int, label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val ink = if (dark) colors.secondary else colors.onSurface
    Row(
        modifier.heightIn(min = 44.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MsIcon(icon, 20.dp, ink)
        // Light: underlined in Ouro, drawn by hand (Compose's underline takes the text's colour).
        val underline = colors.primary
        Text(
            label, color = ink, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            modifier = if (dark) {
                Modifier
            } else {
                Modifier.drawBehind {
                    val y = size.height - 1.5.dp.toPx()
                    drawLine(underline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                }
            },
        )
    }
}

private const val UNAVAILABLE_ALPHA = 0.38f
