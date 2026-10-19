package com.reau.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.data.LogFiles
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.accentTint
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.LocalREauStatus
import com.reau.app.ui.theme.REauMono
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Ver o registro": what the logs hold, inside the app, without sharing them. The log files of [dir] newest first, how
 * many and how big, sharing them from the top bar ([onShare], as Ajustes' "Compartilhar → Registros"); a tap shows the
 * end of one ([LogFiles.tail], the last [TAIL_BYTES]); back goes from the file to the list, then out.
 */
@Composable
fun LogViewerScreen(dir: File?, onBack: () -> Unit, onShare: () -> Unit = {}) {
    var open by remember { mutableStateOf<File?>(null) }
    val goBack = { if (open != null) open = null else onBack() }
    BackHandler(onBack = goBack)
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(
                stringResource(if (open == null) R.string.log_viewer_title else R.string.log_viewer_end_title), goBack, titleTag = "log_viewer_title",
            ) {
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp).testTag("share_logs")) {
                    MsIcon(R.drawable.ic_ms_share, 24.dp, LocalContentColor.current, description = stringResource(R.string.cd_share_logs))
                }
            }
            val file = open
            if (file == null) FileList(dir) { open = it } else FileEnd(file)
        }
    }
}

@Composable
private fun FileList(dir: File?, onOpen: (File) -> Unit) {
    val files = remember(dir) { dir?.let(LogFiles::list).orEmpty() }
    val stamp = remember { SimpleDateFormat("dd/MM HH:mm", Locale.US) }
    val locale = displayLocale()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.log_viewer_summary, files.size, files.size, size(files.sumOf { it.length() }, locale)),
            style = REauMono.copy(fontSize = 11.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("log_summary"),
        )
        if (files.isEmpty()) {
            Text(stringResource(R.string.log_viewer_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            return@Column
        }
        val shape = MaterialTheme.shapes.large
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
            files.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(f) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("log_file_$i"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MsIcon(R.drawable.ic_ms_description, 22.dp, accentTint())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                            style = REauMono.copy(fontSize = 13.5.sp),
                        )
                        Text(
                            "${stamp.format(Date(f.lastModified()))} · ${size(f.length(), locale)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MsIcon(R.drawable.ic_ms_chevron_right, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The end of [file]: what was left out of its start, in blue, then the text in mono, scrolled to its end, never wrapped. */
@Composable
private fun FileEnd(file: File) {
    val tail = remember(file) { runCatching { LogFiles.tail(file, TAIL_BYTES) }.getOrNull() }
    val down = rememberScrollState()
    LaunchedEffect(tail) { down.scrollTo(down.maxValue) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(file.name, style = REauMono.copy(fontSize = 11.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tail == null) {
            Text(stringResource(R.string.log_viewer_unreadable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            return@Column
        }
        if (tail.skippedBytes > 0) Skipped(tail.skippedBytes)
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, hairline(0.14f), MaterialTheme.shapes.small).verticalScroll(down).horizontalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(
                tail.text, softWrap = false, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("log_text"),
                style = REauMono.copy(fontSize = 11.sp, lineHeight = 18.sp),
            )
        }
    }
}

/** How much of the file's start was left out, in the information blue (with its icon, never colour alone). */
@Composable
private fun Skipped(bytes: Long) {
    val info = LocalREauStatus.current.info
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, info.copy(alpha = 0.6f), shape).padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}.testTag("log_skipped"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(R.drawable.ic_ms_info, 18.dp, info)
        Text(stringResource(R.string.log_viewer_skipped, bytes / KB), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A size as the rider reads it: KB below one MB, MB with one decimal (in [locale]'s mark) above. */
private fun size(bytes: Long, locale: Locale): String =
    if (bytes < MB) "${bytes / KB} KB" else String.format(locale, "%.1f MB", bytes.toDouble() / MB)

private const val KB = 1024L
private const val MB = 1024L * 1024L
private const val TAIL_BYTES = 256 * 1024
