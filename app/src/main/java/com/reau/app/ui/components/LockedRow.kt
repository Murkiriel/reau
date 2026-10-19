package com.reau.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.reau.app.ui.theme.REauMono

/** A fixed setting: its title, what it is and why, and the value at the right, read only (tag `<tag>_locked`; its (i), if any, `<tag>_info`). */
@Composable
fun LockedRow(title: String, value: String, tag: String, sub: String? = null, info: Info? = null) {
    SettingRow(
        title, sub, false, info, null, tag, Modifier.semantics(mergeDescendants = true) {}.testTag("${tag}_locked"), null,
        trailing = {
            Text(
                value, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = REauMono.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        },
    )
}
