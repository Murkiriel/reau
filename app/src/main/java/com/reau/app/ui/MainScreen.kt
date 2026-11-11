package com.reau.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.ui.MainLook.Warning
import com.reau.app.ui.components.BridgeCard
import com.reau.app.ui.components.BridgeSide
import com.reau.app.ui.components.ComponentLogic
import com.reau.app.ui.components.GhostButton
import com.reau.app.ui.components.GroupLabel
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.theme.REauMono
import com.reau.app.ui.theme.LocalREauNight
import com.reau.app.ui.theme.REauTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's look: the Aurum theme, dark or light as the rider's "Tema" says (automatic by the sun, always light,
 * always dark), handed down by MainActivity ([LocalREauNight]); dark by default.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    REauTheme(dark = LocalREauNight.current) {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}

/**
 * The main screen: the warnings while something is wrong, the bridge card with both sides and Ligar/Desligar, the
 * card mode's link while stopped ([MainExtras.onCard]), the shortcuts, the installed Android Auto and REau's version.
 * Only the content under the fixed top bar scrolls; stopped and with no warning it all fits. [clock] is
 * SystemClock.elapsedRealtime, for the time on Desligar.
 */
@Suppress("LongParameterList") // the state, its four ways out, and the clock the tests set
@Composable
fun MainScreen(
    state: MainUiState,
    onToggle: () -> Unit,
    onOpenAndroidAutoSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    extras: MainExtras = MainExtras(),
    clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    val p = state.projection
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize().testTag("main_scroll").verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    // Stopped and with nothing wrong, all of it fits without scrolling.
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (warning in MainLook.warnings(p, state.battery, state.vpnHoldsREau, state.otherEditionInstalled, state.locationOff)) {
                        Warning(warning, state, onOpenAndroidAutoSettings, extras)
                    }
                    BridgeCard(
                        headlineText(p), subText(p), androidAutoSide(state), dashSide(p),
                        on = p.phase != Phase.STOPPED, elapsed = rememberElapsed(p, state.startedAtMs, clock), onToggle = onToggle,
                        // The network server's icon; the card mode sends a picture and has no server to show.
                        aaOverlay = {
                            if (!p.card) {
                                ServerBadge(state.server.networkOn, extras.onOpenServer, Modifier.align(Alignment.TopEnd))
                            }
                        },
                        stopping = p.stopping,
                    )
                    if (p.phase == Phase.STOPPED && !p.stopping) {
                        GhostButton(
                            R.drawable.ic_ms_image, stringResource(R.string.action_card), "card", extras.onCard,
                            Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    GroupLabel(null, stringResource(R.string.shortcuts_label))
                    Shortcuts(state, onOpenAndroidAutoSettings, onOpenSettings, extras, say)
                    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val androidAuto = state.androidAutoVersion?.let { stringResource(R.string.aa_version, it) }
                        FootLine(androidAuto ?: stringResource(R.string.aa_not_installed), "aa_version")
                        // REau's own version under Android Auto's.
                        FootLine(stringResource(R.string.reau_version, state.reauVersion), "reau_version")
                        // The old app with the seal on its icon, if it is still installed: it is not needed any more.
                        if (state.otherEditionInstalled) FootLine(stringResource(R.string.old_app_note), "old_app_note")
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    Snackbar(it, Modifier.testTag("snackbar"), shape = MaterialTheme.shapes.small)
                }
            }
        }
    }
}

/** One line of the foot: the installed Android Auto, REau's own version. */
@Composable
private fun FootLine(text: String, tag: String) {
    Text(
        text, style = REauMono.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** The time since the projection started, a second at a time, while it is not stopped. */
@Composable
private fun rememberElapsed(p: Projection, startedAtMs: Long?, clock: () -> Long): String? {
    var now by remember { mutableLongStateOf(clock()) }
    val running = p.phase != Phase.STOPPED && startedAtMs != null
    LaunchedEffect(running) {
        while (running) {
            now = clock()
            delay(TICK_MS)
        }
    }
    return if (running && startedAtMs != null) ComponentLogic.elapsed(now - startedAtMs) else null
}

/** The Android Auto side, or the card mode's picture in its place. */
@Composable
private fun androidAutoSide(state: MainUiState): BridgeSide {
    val p = state.projection
    val kind = MainLook.androidAutoKind(p)
    return if (p.card) {
        BridgeSide(stringResource(R.string.side_label_picture), cardText(state.cardName), kind, "side_picture")
    } else {
        val value = if (p.phase == Phase.PREPARING) R.string.link_connecting else ScreenText.androidAuto(p.androidAuto)
        BridgeSide(stringResource(R.string.side_label_android_auto), stringResource(value), kind, "side_android_auto")
    }
}

@Composable
private fun dashSide(p: Projection): BridgeSide {
    val value = if (p.phase == Phase.PREPARING) R.string.link_connecting else ScreenText.dash(p)
    return BridgeSide(stringResource(R.string.side_label_dash), stringResource(value), MainLook.dashKind(p), "side_dash")
}

/**
 * Which picture the card mode sends: our own default one, "Wallpaper", the picked one, by [name], or the
 * legibility card.
 */
@Composable
internal fun cardText(name: String?): String = when {
    name == null -> stringResource(R.string.card_legibility)
    name == com.reau.app.card.CardName.of(com.reau.app.settings.REauSettings.DEFAULT_CARD) -> stringResource(R.string.card_default)
    else -> stringResource(R.string.card_picture, name)
}

@Composable
private fun headlineText(p: Projection): String = stringResource(ScreenText.headline(p.headline))

/** The headline's line; a start that failed says its reason there. */
@Composable
private fun subText(p: Projection): String {
    if (p.headline == Headline.DASH_LOST) {
        val now = rememberNowWhile(true)
        val (res, secs) = DashLostText.text(p.dashGoneAtMs, now)
        return if (secs == null) stringResource(res) else stringResource(res, secs)
    }
    return MainLook.sub(p)?.let { stringResource(it) } ?: p.problem.orEmpty()
}

/** The monotonic clock, read again each second while [active]. */
@Composable
private fun rememberNowWhile(active: Boolean): Long {
    var now by remember { mutableLongStateOf(com.reau.app.util.MonotonicClock.nowMs()) }
    LaunchedEffect(active) {
        while (active) {
            now = com.reau.app.util.MonotonicClock.nowMs()
            delay(TICK_MS)
        }
    }
    return now
}

private const val TICK_MS = 1000L
