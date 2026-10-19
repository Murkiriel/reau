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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.service.Phase
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.GroupLabel
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.InfoSheet
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.color
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.LocalREauStatus

/**
 * "Android Auto na rede" (the same name in the bar and in its (i)), the screen the server's icon opens. It is about
 * the **network server**, the browser's ("Ver no navegador"): whether it is up, the switch that turns it on (only
 * after Ligar, and explicitly), the address and PIN, the touch, and the sound's choice. Its rule is said on it: with
 * the network server on, REau does not look for the dash, since the point is to run Android Auto on something that is
 * not the dash.
 *
 * [state] is the main screen's, so it follows the projection; [settings] and [change] are the settings' own.
 */
@Composable
fun ServerScreen(state: MainUiState, settings: REauSettings, change: (REauSettings) -> Unit, onBack: () -> Unit) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(
                stringResource(R.string.server_screen_title), onBack, titleTag = "server_screen_title",
                titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = TITLE_SP.sp, lineHeight = (TITLE_SP + 4).sp),
                actions = {
                    IconButton({ showInfo = true }, Modifier.size(48.dp).testTag("server_info")) {
                        MsIcon(R.drawable.ic_ms_info, 24.dp, LocalContentColor.current, description = stringResource(R.string.cd_info))
                    }
                },
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
            ) {
                NetworkCard(state)
                WebSection(settings, projectionOn = state.projection.phase != Phase.STOPPED, change = change)
                AudioSection(settings, change)
            }
        }
        if (showInfo) {
            InfoSheet(Info(stringResource(R.string.server_screen_title), stringResource(R.string.server_screen_info))) { showInfo = false }
        }
    }
}

/**
 * The network server at a glance: its icon (blinking in green while it is up) in a Carvão circle like the main screen's
 * sides, the state in words and what to do next, and the rule: on, the dash is not looked for.
 */
@Composable
private fun NetworkCard(state: MainUiState) {
    val on = state.server.networkOn
    val projectionOn = state.projection.phase != Phase.STOPPED
    val shape = MaterialTheme.shapes.large
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        GroupLabel(null, stringResource(R.string.server_group_network))
        Column(
            Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer).border(1.dp, hairline(0.22f), shape).padding(16.dp)
                .testTag("server_status_card"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(GLYPH_DP.dp).background(LocalREauStatus.current.chrome, CircleShape), contentAlignment = Alignment.Center) {
                    ServerGlyph(on, GLYPH_ICON_DP.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (on) R.string.server_network_on else R.string.server_network_off), style = MaterialTheme.typography.titleLarge,
                        color = if (on) StatusKind.OK.color() else colors.onSurface, modifier = Modifier.testTag("server_state"),
                    )
                    val sub = when {
                        on -> R.string.server_network_sub_on
                        projectionOn -> R.string.server_network_sub_off_running
                        else -> R.string.server_network_sub_off_stopped
                    }
                    Text(
                        stringResource(sub), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.testTag("server_network_sub"),
                    )
                }
            }
            Text(
                stringResource(R.string.server_rule), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.testTag("server_rule"),
            )
        }
    }
}

private val GROUP_GAP = 18.dp
private const val TITLE_SP = 20
private const val GLYPH_DP = 56
private const val GLYPH_ICON_DP = 30
