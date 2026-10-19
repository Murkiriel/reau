package com.reau.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reau.app.Edition
import com.reau.app.R
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.color

/**
 * Every way to Android's Accessibility page asks first: with the service allowed, some apps, a bank's among them, can
 * refuse to run or stop working, and the rider should choose that knowing it. [content] gets the function to call where
 * it used to call [onOpen]; the page opens only once the rider confirms. The Lite edition has no accessibility service,
 * so there the dialog never opens: [content]'s function does nothing.
 */
@Composable
internal fun AccessibilityAsk(onOpen: () -> Unit, content: @Composable (ask: () -> Unit) -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    content { asking = Edition.hasAccessibility }
    if (asking) {
        val colors = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { asking = false },
            icon = { MsIcon(R.drawable.ic_ms_report, 28.dp, StatusKind.CONNECTING.color()) },
            title = { Text(stringResource(R.string.accessibility_ask_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.accessibility_ask_text)) },
            confirmButton = {
                OutlinedButton(
                    {
                        asking = false
                        onOpen()
                    },
                    Modifier.heightIn(min = 44.dp).testTag("accessibility_confirm"), shape = MaterialTheme.shapes.small,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                ) { Text(stringResource(R.string.accessibility_ask_confirm)) }
            },
            dismissButton = {
                TextButton({ asking = false }, Modifier.heightIn(min = 44.dp).testTag("accessibility_cancel")) {
                    Text(stringResource(R.string.action_cancel), color = colors.onSurfaceVariant)
                }
            },
            containerColor = colors.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
        )
    }
}
