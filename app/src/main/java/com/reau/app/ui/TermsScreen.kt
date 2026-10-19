package com.reau.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.settings.Terms
import com.reau.app.settings.Terms.Block
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.GoldButton
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.SupportButton
import com.reau.app.ui.components.TabOption
import com.reau.app.ui.components.TabRail
import com.reau.app.ui.components.hairline
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The terms of use, in Portuguese and in English. With [gate] set it is the screen the rider meets before anything
 * else until the current version is accepted: the two tabs (the Portuguese first), no way back to the app, the two
 * buttons at the foot ("Li e aceito" / "Não aceito") and the system's back as a refusal. The two buttons only appear
 * once the text has been read to its end (scrolled there, or taken there by the one button the foot shows until then,
 * "Descer até o fim", which rolls the text on steadily at a reading pace, [TermsScroll], and which a touch on the text
 * interrupts); a text that fits the screen counts as read. Without it, from the
 * settings, it is only a page to read, in the app's own language alone.
 */
@Composable
fun TermsScreen(gate: Boolean, onAccept: () -> Unit, onDecline: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val appLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val appIndex = if (Terms.opensInPortuguese(appLanguage)) 0 else 1
    var picked by rememberSaveable { mutableIntStateOf(appIndex) }
    // Before accepting, the two tabs (the rider may read either); once accepted, only the app's own language.
    val language = if (gate) picked else appIndex
    val portuguese = remember { Terms.load(context, Terms.ASSET_PT) }
    val english = remember { Terms.load(context, Terms.ASSET_EN) }
    BackHandler(onBack = if (gate) onDecline else onBack)
    val scroll = rememberScrollState()
    // Once the end was reached it stays reached (the other tab, a turn of the phone): the rider has read the terms.
    var readToEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { snapshotFlow { !scroll.canScrollForward }.collect { if (it) readToEnd = true } }
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(onBack = if (gate) null else onBack, markBesideName = true)
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp).testTag("terms_text"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (gate) {
                    TabRail(
                        listOf(
                            TabOption(stringResource(R.string.terms_lang_pt), null, "terms_lang_pt"),
                            TabOption(stringResource(R.string.terms_lang_en), null, "terms_lang_en"),
                        ),
                        language, { picked = it },
                    )
                }
                (if (language == 0) portuguese else english).forEach { BlockView(it) }
            }
            if (gate) TermsFoot(readToEnd, language, scroll, onAccept, onDecline)
        }
    }
}

/**
 * The roll is the point of the button (the rider must see the text go by), so it keeps its own pace even where the phone's
 * animation scales are off ("remove animations", scale 0), which would otherwise end it at once.
 */
private object FullSpeedClock : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** The foot of the gate: "Descer até o fim" until the end is read, then "Não aceito" and "Li e aceito". */
@Composable
private fun TermsFoot(readToEnd: Boolean, language: Int, scroll: ScrollState, onAccept: () -> Unit, onDecline: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    // True while the button is rolling the text by itself; a touch on the text cancels the roll and clears it.
    var autoScrolling by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The buttons speak the language of the tab being read, not the app's.
        if (readToEnd) {
            val decline = stringResource(if (language == 0) R.string.terms_decline_pt else R.string.terms_decline_en)
            val accept = stringResource(if (language == 0) R.string.terms_accept_pt else R.string.terms_accept_en)
            SupportButton(CardAction(decline, "terms_decline", onDecline), Modifier.weight(1f))
            GoldButton(CardAction(accept, "terms_accept", onAccept), Modifier.weight(1f))
        } else {
            val down = stringResource(if (language == 0) R.string.terms_scroll_pt else R.string.terms_scroll_en)
            val reading = stringResource(if (language == 0) R.string.terms_scrolling_pt else R.string.terms_scrolling_en)
            val goToEnd: () -> Unit = {
                if (!autoScrolling) {
                    scope.launch {
                        autoScrolling = true
                        try {
                            val distance = scroll.maxValue - scroll.value
                            val ms = TermsScroll.durationMs(distance, density)
                            // Nothing left to roll (the text fits): no animation, the end is already here.
                            if (distance > 0) {
                                withContext(FullSpeedClock) { scroll.animateScrollTo(scroll.maxValue, tween<Float>(ms, easing = LinearEasing)) }
                            }
                        } finally {
                            // Also on a rider's drag, which cancels the roll: the button is "Descer até o fim" again.
                            autoScrolling = false
                        }
                    }
                }
            }
            val action = CardAction(if (autoScrolling) reading else down, "terms_scroll_end", goToEnd)
            GoldButton(action, Modifier.weight(1f), enabled = !autoScrolling)
        }
    }
}

@Composable
private fun BlockView(block: Block) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is Block.Title -> Text(
            block.text, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        is Block.Heading -> Text(
            block.text, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.padding(top = 8.dp),
        )
        is Block.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = 0.86f))
        is Block.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Text(block.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = 0.86f))
        }
        is Block.Quote -> {
            val shape = MaterialTheme.shapes.small
            Text(
                block.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer).border(1.dp, hairline(0.18f), shape).padding(12.dp),
            )
        }
    }
}
