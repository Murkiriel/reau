package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.ui.theme.REauMono

/**
 * A line of the screen "Dados da moto" that only tells: [title] at the left, what the dash said ([value]) at the right
 * (in mono: it is mostly numbers and codes), and under the title an optional [sub] (what it is, or when it was read).
 * The whole row is one thing for a screen reader, tagged [tag]; an [info] puts the (i) beside the title (`<tag>_info`).
 */
@Composable
fun ValueRow(title: String, value: String, tag: String, sub: String? = null, mono: Boolean = true, @DrawableRes icon: Int? = null, info: Info? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { MsIcon(it, 28.dp, colors.primary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f, fill = false))
                if (info != null) InfoButton(info, "${tag}_info")
            }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        Text(
            value, color = colors.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
            style = if (mono) REauMono.copy(fontSize = 13.sp) else MaterialTheme.typography.bodyMedium,
        )
    }
}
