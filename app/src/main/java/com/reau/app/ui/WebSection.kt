package com.reau.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.settings.WebAudioMode
import com.reau.app.ui.components.GhostButton
import com.reau.app.ui.components.GroupLabel
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.LockedRow
import com.reau.app.ui.components.SegmentedRow
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauMono
import com.reau.app.web.WebAccess
import com.reau.app.web.WebAddress
import java.net.InetAddress

/** The phone's own addresses, read each time the section draws; a test gives a fixed list. */
internal val LocalWebAddresses = staticCompositionLocalOf<() -> List<InetAddress>> { { WebAddress.phoneAddresses() } }

/**
 * The browser section, on the network server's screen: the switch that makes REau serve Android Auto on the local
 * network, the one that lets the browser touch, and, with the server up, the address, the PIN, how many browsers are
 * in and the way to drop them. Spaced as the fit's and the stats' cards (the screen's own constants).
 */
@Composable
internal fun WebSection(s: REauSettings, projectionOn: Boolean = true, change: (REauSettings) -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        GroupLabel(null, stringResource(R.string.section_web))
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
            if (projectionOn) {
                SettingSwitch(R.string.web_view_title, R.string.web_view_sub, R.string.web_view_long, s.webView, "web_view") {
                    change(s.copy(webView = it))
                }
            } else {
                // The server is switched on after Ligar, never before (the switch is off at each start).
                LockedRow(
                    stringResource(R.string.web_view_title), stringResource(R.string.web_view_wait_value), "web_view",
                    stringResource(R.string.web_view_wait_sub),
                    Info(stringResource(R.string.web_view_title), stringResource(R.string.web_view_long)),
                )
            }
            if (s.webView) {
                HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                SettingSwitch(R.string.web_touch_title, R.string.web_touch_sub, R.string.web_touch_long, s.webTouch, "web_touch") {
                    change(s.copy(webTouch = it))
                }
                HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                WebAccessBlock()
            }
        }
    }
}

/**
 * Where Android Auto's sound goes (a section of its own on the server screen): on the phone only (as always), in the
 * browser only, or in both. Always there: with the sinks announced REau plays the phone's sound itself, browser switch
 * on or not, and a mode left on Navegador or Os dois must be seen to be changed back.
 */
@Composable
internal fun AudioSection(s: REauSettings, change: (REauSettings) -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        GroupLabel(null, stringResource(R.string.server_group_sound))
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
            WebAudioChoice(s, change)
        }
    }
}

@Composable
private fun WebAudioChoice(s: REauSettings, change: (REauSettings) -> Unit) {
    val modes = WebAudioMode.entries
    SegmentedRow(
        stringResource(R.string.web_audio_title),
        listOf(
            stringResource(R.string.web_audio_phone) to "web_audio_phone",
            stringResource(R.string.web_audio_browser) to "web_audio_browser",
            stringResource(R.string.web_audio_both) to "web_audio_both",
        ),
        modes.indexOf(s.webAudio), { change(s.copy(webAudio = modes[it])) },
        info = Info(stringResource(R.string.web_audio_title), stringResource(R.string.web_audio_long)),
        tag = "web_audio",
    )
}

/** The warning, then the PIN and the address (or why there is none yet), the count and the way to drop the browsers. */
@Composable
private fun WebAccessBlock() {
    val access by WebAccess.state.collectAsState()
    val addresses = LocalWebAddresses.current
    val networkTick = rememberNetworkTick()
    Column(Modifier.fillMaxWidth().padding(CARD_PADDING).testTag("web_access"), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        Text(
            stringResource(R.string.web_warning), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("web_warning"),
        )
        val now = access
        if (now == null) {
            Text(stringResource(R.string.web_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("web_waiting"))
        } else {
            PinRow(now.pin)
            val urls = remember(now.port, now.viewers, networkTick) { WebAddress.urls(addresses(), now.port) }
            if (urls.isEmpty()) {
                Text(stringResource(R.string.web_no_address), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("web_no_address"))
            }
            urls.forEachIndexed { i, url -> AddressRow(url, "web_address_$i") }
            Text(
                stringResource(WebAddress.viewersText(now.viewers), now.viewers), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("web_viewers"),
            )
            if (now.viewers > 0) GhostButton(R.drawable.ic_ms_block, stringResource(R.string.web_kick_all), "web_kick", { WebAccess.kickAll() })
        }
    }
}

/** The PIN the browser asks for, big and in mono, so it is read and typed at a glance. */
@Composable
private fun PinRow(pin: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.web_pin_label), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            pin, style = REauMono.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("web_pin"),
        )
    }
}

/** One address in mono, selectable, with the button that copies it (the button says so for a moment). */
@Composable
private fun AddressRow(url: String, tag: String) {
    val context = LocalContext.current
    var copied by remember(url) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(url, style = REauMono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag(tag))
        }
        GhostButton(
            R.drawable.ic_ms_content_copy, stringResource(if (copied) R.string.web_copied else R.string.web_copy), "${tag}_copy",
            {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("REau", url))
                copied = true
            },
        )
    }
}
