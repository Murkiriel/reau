package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.settings.Credits
import com.reau.app.ui.components.BrandIcon
import com.reau.app.ui.components.BrandName
import com.reau.app.ui.components.CreditRow
import com.reau.app.ui.components.GroupLabel
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.brandGold
import com.reau.app.ui.components.hairline
import kotlinx.coroutines.launch

/**
 * The credits page: "REau" and the independence line on top; one project per line, the name, "© holder · licence" and
 * its role, in three groups ([Credits]). A tap shows the project's address, hands it to [onCopy] (the clipboard: REau
 * opens no browser itself) and says so in a Snackbar.
 */
@Composable
fun CreditsScreen(onBack: () -> Unit, onCopy: (String) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.credits_copied_short)
    var shown by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sourceShown by rememberSaveable { mutableStateOf(false) }
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.credits_title), onBack, titleTag = "credits_title")
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BrandIcon(52.dp, tag = "credits_brand_icon")
                            BrandName(34.sp, re = MaterialTheme.colorScheme.onSurface, au = brandGold(), tag = "credits_brand")
                        }
                        Text(
                            stringResource(R.string.credits_independent), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("credits_independent"),
                        )
                    }
                    Group(stringResource(R.string.credits_source_group), "credits_group_source") {
                        CreditRow("REau", "© 2026 REau · AGPL-3.0", stringResource(R.string.credits_source_role), {
                            sourceShown = true
                            onCopy(Credits.SOURCE_URL)
                            scope.launch { snackbar.showSnackbar(copiedText) }
                        }, "credit_source", shown = Credits.SOURCE_URL.takeIf { sourceShown })
                    }
                    for (group in Credits.groups) {
                        Group(stringResource(sideTitle(group.side)), "credits_group_${group.side}") {
                            group.entries.forEachIndexed { i, entry ->
                                if (i > 0) HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                                val holder = if (entry.holderText != 0) stringResource(entry.holderText) else entry.holder
                                val line = if (holder.isBlank()) entry.licence else "$holder · ${entry.licence}"
                                val name = if (entry.nameText != 0) stringResource(entry.nameText) else entry.name
                                CreditRow(name, line, stringResource(entry.role), {
                                    shown = shown + entry.name
                                    onCopy(entry.url)
                                    scope.launch { snackbar.showSnackbar(copiedText) }
                                }, "credit_${entry.name}", shown = entry.url.takeIf { entry.name in shown })
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    Snackbar(it, Modifier.testTag("snackbar"), shape = MaterialTheme.shapes.small)
                }
            }
        }
    }
}

/** A group: its label and a Carvão card with its lines. */
@Composable
private fun Group(title: String, tag: String, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupLabel(null, title, Modifier.testTag(tag))
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
            content()
        }
    }
}

private fun sideTitle(side: Credits.Side): Int = when (side) {
    Credits.Side.ANDROID_AUTO -> R.string.credits_android_auto
    Credits.Side.DASH -> R.string.credits_dash
    Credits.Side.LIBRARIES -> R.string.credits_libraries
}
