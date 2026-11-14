package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.aa.AaScreen
import com.reau.app.settings.FitDefaults
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.GoldButton
import com.reau.app.ui.components.SupportButton
import com.reau.app.ui.theme.REauMono
import com.reau.app.ui.theme.REauSpace

/**
 * The fit assistant ([FitAssistant]) as a card at the top of "Encaixe na cúpula", under the picture and its mask, which stay in sight while
 * the card scrolls: the step's text, what is in the way (Android Auto not on a navigation app, a density the factory fit was not measured
 * at), the counters of the step after a "no", and the buttons, all 48 dp tall or more. Every counter is the navigation tab's own and is
 * saved as it is pressed, as on the tab below; [onClose] is the end of the assistant, finished or skipped.
 */
@Composable
internal fun FitAssistantCard(s: REauSettings, change: (REauSettings) -> Unit, screen: AaScreen, onClose: () -> Unit) {
    var stepAt by rememberSaveable { mutableIntStateOf(FitAssistant.Step.START.ordinal) }
    var adjusting by rememberSaveable { mutableStateOf(false) }
    val state = FitAssistant.State(FitAssistant.Step.entries[stepAt], adjusting)
    val go = { to: FitAssistant.State ->
        stepAt = to.step.ordinal
        adjusting = to.adjusting
    }
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer).border(1.dp, colors.primary, shape).padding(CARD_PADDING)
            .testTag("fit_assistant"),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.fit_assistant_open), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = colors.onSurface,
            )
            Text(
                stringResource(R.string.fit_assistant_step, state.step.ordinal + 1, FitAssistant.Step.entries.size),
                Modifier.testTag("fit_assistant_step"), style = REauMono, color = colors.onSurfaceVariant,
            )
        }
        Text(
            stringResource(stepText(state.step)), Modifier.testTag("fit_assistant_text"),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
        )
        if (!FitAssistant.navigationShown(screen) && state.step != FitAssistant.Step.END) {
            Note(stringResource(R.string.fit_assistant_no_nav), "fit_assistant_no_nav")
        }
        if (!FitAssistant.densityMeasured(s) && state.step == FitAssistant.Step.START) {
            Note(stringResource(R.string.fit_assistant_density, s.density, FitDefaults.MEASURED_DENSITY), "fit_assistant_density")
        }
        if (state.adjusting) AssistantCounters(state.step, s, change)
        AssistantButtons(state, go, { FitAssistant.started(s).let { if (it != s) change(it) } }, onClose)
    }
}

/** The counters of [step] after a "no": the navigation tab's own, with the same names, ranges and steps, under a line that says what they do. */
@Composable
private fun AssistantCounters(step: FitAssistant.Step, s: REauSettings, change: (REauSettings) -> Unit) {
    val controls = FitAssistant.controls(step, s.navFit.fit)
    val tip = if (FitControl.CROP_SIZE in controls) R.string.fit_assistant_size_tip else R.string.fit_assistant_adjust_tip
    Note(stringResource(tip), "fit_assistant_tip")
    FitCounters(s.navFit, s.content, controls, { change(s.copy(navFit = it)) }, tags = "fit_assistant_")
}

/**
 * The buttons of a step: the answer on the first row (one golden button at most), the way back and the way out on the second. "Começar"
 * also does [onStart]; "Pular" and "Concluir" are [onClose].
 */
@Composable
private fun AssistantButtons(state: FitAssistant.State, go: (FitAssistant.State) -> Unit, onStart: () -> Unit, onClose: () -> Unit) {
    val step = state.step
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            step == FitAssistant.Step.END -> GoldButton(
                CardAction(stringResource(R.string.fit_assistant_finish), "fit_assistant_finish", onClose), Modifier.weight(1f),
            )
            step == FitAssistant.Step.START -> GoldButton(
                CardAction(stringResource(R.string.fit_assistant_begin), "fit_assistant_begin") {
                    onStart()
                    go(state.forward())
                },
                Modifier.weight(1f),
            )
            state.adjusting -> GoldButton(
                CardAction(stringResource(R.string.fit_assistant_good), "fit_assistant_good") { go(state.forward()) }, Modifier.weight(1f),
            )
            else -> {
                SupportButton(CardAction(stringResource(R.string.fit_assistant_no), "fit_assistant_no") { go(state.adjust()) }, Modifier.weight(1f))
                GoldButton(CardAction(stringResource(R.string.fit_assistant_yes), "fit_assistant_yes") { go(state.forward()) }, Modifier.weight(1f))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (step != FitAssistant.Step.START) QuietButton(stringResource(R.string.action_back), "fit_assistant_back") { go(state.back()) }
        if (step != FitAssistant.Step.END) QuietButton(stringResource(R.string.fit_assistant_skip), "fit_assistant_skip", onClose)
    }
}

/** A text button of the assistant ("Voltar", "Pular"), 48 dp tall. */
@Composable
private fun QuietButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = REauSpace.touch).testTag(tag)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A smaller line of the assistant: something in the way, or what the counters under it do. */
@Composable
private fun Note(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun stepText(step: FitAssistant.Step): Int = when (step) {
    FitAssistant.Step.START -> R.string.fit_assistant_start_text
    FitAssistant.Step.MANEUVER -> R.string.fit_assistant_maneuver_text
    FitAssistant.Step.SPEED -> R.string.fit_assistant_speed_text
    FitAssistant.Step.END -> R.string.fit_assistant_end_text
}
